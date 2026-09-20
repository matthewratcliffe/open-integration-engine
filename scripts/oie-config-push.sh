#!/usr/bin/env bash
#
# Pushes the declarative configuration in config/ to a running engine and
# deploys it. Designed to be the only thing a GitLab CI deploy job runs.
#
#   ./scripts/oie-config-push.sh [--dry-run] [--no-deploy] [--prune]
#                                [--wait-cluster[=<seconds>]]
#
# What it pushes, in dependency order:
#   1. config/code-templates/libraries.xml   library definitions + membership
#   2. config/code-templates/templates/*.xml individual code templates
#   3. config/channel-groups.xml             channel group tree
#   4. config/channels/*.xml                 one file per channel
#   5. config/configuration-map.properties   the configuration map
#   6. deploy every channel that was created or updated
#   7. with --wait-cluster: wait for every other engine to deploy it too
#
# --wait-cluster is for a multi-engine deployment (see docs/multi-pod.md). The
# deploy above reaches whichever engine answered, and the cluster extension
# carries it to the rest; without this the pipeline goes green while the other
# engines are still catching up -- or while one of them is failing to. A green
# pipeline has to mean every server deployed it, not just the one that answered.
#
# Every step is an upsert keyed on the id inside the XML, so re-running against
# an already-current server is a no-op apart from the deploy. Channel ids must
# be stable -- they are what makes this idempotent -- so always export channels
# from the Administrator rather than hand-writing new ids.
#
# Requires: bash, curl, xmllint (libxml2-utils / libxml2)

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CONFIG_DIR="${OIE_CONFIG_DIR:-${ROOT}/config}"

# shellcheck source=scripts/oie-api.sh
source "${ROOT}/scripts/oie-api.sh"

DRY_RUN=false
DO_DEPLOY=true
DO_PRUNE=false
# 0 means "do not wait". Kept as a number rather than a flag plus a number so
# --wait-cluster=0 in a shared CI variable reads as "off" rather than as an
# infinite wait.
WAIT_CLUSTER=0

while [[ $# -gt 0 ]]; do
    case "$1" in
        --dry-run)    DRY_RUN=true ;;
        --no-deploy)  DO_DEPLOY=false ;;
        --prune)      DO_PRUNE=true ;;
        --wait-cluster) WAIT_CLUSTER="${OIE_CLUSTER_WAIT:-180}" ;;
        --wait-cluster=*)
            WAIT_CLUSTER="${1#*=}"
            [[ "$WAIT_CLUSTER" =~ ^[0-9]+$ ]] || {
                printf -- '--wait-cluster takes a number of seconds\n' >&2
                exit 2
            } ;;
        -h|--help)
            sed -n '2,36p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
            exit 0 ;;
        *)
            printf 'unknown argument: %s\n' "$1" >&2
            exit 2 ;;
    esac
    shift
done

command -v xmllint >/dev/null 2>&1 || {
    printf 'xmllint not found. Install libxml2-utils (Debian/Ubuntu or Alpine).\n' >&2
    exit 1
}

########################################################################
# Output helpers
########################################################################
if [[ -t 1 ]]; then
    C_RESET=$'\033[0m'; C_DIM=$'\033[2m'; C_GREEN=$'\033[32m'
    C_YELLOW=$'\033[33m'; C_RED=$'\033[31m'; C_BOLD=$'\033[1m'
else
    C_RESET=""; C_DIM=""; C_GREEN=""; C_YELLOW=""; C_RED=""; C_BOLD=""
fi

step()  { printf '\n%s==> %s%s\n' "$C_BOLD" "$*" "$C_RESET"; }
ok()    { printf '    %s%s%s\n' "$C_GREEN" "$*" "$C_RESET"; }
skip()  { printf '    %s%s%s\n' "$C_DIM" "$*" "$C_RESET"; }
warn()  { printf '    %s%s%s\n' "$C_YELLOW" "$*" "$C_RESET" >&2; }
fail()  { printf '    %s%s%s\n' "$C_RED" "$*" "$C_RESET" >&2; FAILURES=$((FAILURES + 1)); }

FAILURES=0
CHANGED_CHANNELS=()

# xpath_value <xpath> <file> -- empty string when the node is absent.
xpath_value() {
    xmllint --xpath "string($1)" "$2" 2>/dev/null || true
}

# verify_channel_valid <id> <label>
#
# A channel whose XML the server cannot deserialise is NOT rejected: it is
# stored as an InvalidChannel, and both PUT /channels/{id} and
# POST /channels/{id}/_deploy still answer 2xx. Without this check a typo in a
# connector property (say useHeadersVariable for useResponseHeadersVariable)
# produces a completely green pipeline and a channel that never runs.
#
# The server strips an invalid channel down to a stub: no class attribute on the
# source connector's properties, no destination connectors, and a description
# saying so. Read it back and insist on the real thing.
verify_channel_valid() {
    local id="$1" label="$2" xml
    if [[ "$DRY_RUN" == "true" ]]; then
        return 0
    fi

    if ! xml="$(oie_api GET "/channels/${id}")"; then
        fail "${label}: could not read the channel back (HTTP $(oie_last_status))"
        return 1
    fi

    if [[ "$xml" == *"This channel is invalid"* ]] \
       || [[ "$xml" != *"<properties class="* ]]; then
        fail "${label}: stored as an invalid channel -- the server could not deserialise it"
        printf '        %s\n' \
            "Usually an unknown or misspelled element inside a connector's <properties>," \
            "or an extension the server does not have installed." >&2
        return 1
    fi
    CHANGED_CHANNELS+=("$id")
    return 0
}

# verify_channel_deployed <id> <label> -- confirms the channel actually reached a
# deployed state, since _deploy answers 204 whether or not it worked.
verify_channel_deployed() {
    local id="$1" label="$2" xml state
    if [[ "$DRY_RUN" == "true" ]]; then
        return 0
    fi

    if ! xml="$(oie_api GET "/channels/${id}/status")" || [[ -z "$xml" ]]; then
        fail "${label}: deployed but has no dashboard status, so it is not running"
        return 1
    fi

    # Matched in-shell rather than with `sed ... | head -1`: head closes the pipe
    # after the first line, sed takes SIGPIPE, and `set -o pipefail` then kills
    # the whole script with 141.
    state=""
    if [[ "$xml" =~ \<state\>([A-Z_]+)\</state\> ]]; then
        state="${BASH_REMATCH[1]}"
    fi
    case "$state" in
        STARTED|PAUSED|STOPPED)
            ok "deployed ${label} (${state})"
            return 0 ;;
        *)
            fail "${label}: unexpected state after deploy: ${state:-none}"
            return 1 ;;
    esac
}

# wait_for_cluster <seconds>
#
# Blocks until every live engine reports the channels this run deployed as
# deployed at the same sequence, or until the time runs out.
#
# The deploy above went to one engine. In a cluster that engine records what was
# asked for and the others converge on it within a few seconds, so a pipeline
# that stops at the first 204 reports success for a cluster that is still one
# engine short -- or that has a node failing to deploy the channel at all, which
# is the case worth failing a pipeline over.
#
# Reads /api/cluster/status, which every node serves and which answers for all
# of them out of the shared database. Rows are tab-separated (see Tsv.java in
# https://github.com/gibson9583/oie-cluster), and the fields used here are:
#
#   1 channelId   2 name   ... 11 converged   12 expected   13 errors
wait_for_cluster() {
    local timeout="$1"
    local deadline=$((SECONDS + timeout))
    local xml rows_xml enabled line id name done_ expected errors
    local pending failed first=true
    # Handed to awk as a variable rather than written as an escape in the
    # program, because busybox awk -- which is the awk in an Alpine CI image --
    # does not read \x escapes the way gawk does.
    local SEP
    SEP="$(printf '\037')"

    while :; do
        if ! xml="$(oie_api GET /cluster/status)"; then
            fail "the cluster API did not answer. Is the Cluster extension installed?"
            return 1
        fi

        enabled="$(printf '%s' "$xml" \
            | xmllint --xpath 'string(//entry[string="enabled"]/boolean)' - 2>/dev/null || true)"
        if [[ "$enabled" != "true" ]]; then
            fail "this engine is not part of a cluster (OIE_CLUSTER_ENABLED is not true)"
            return 1
        fi

        rows_xml="$(printf '%s' "$xml" \
            | xmllint --xpath '//entry[string="channelRows"]/list' - 2>/dev/null || true)"

        pending=0
        failed=0
        local report=()
        while IFS= read -r line; do
            [[ -n "$line" ]] || continue
            # Fields are pulled with awk rather than with `IFS=$'\t' read`,
            # because bash treats a run of tabs as one delimiter -- tab is IFS
            # whitespace -- and an empty field in the middle of the row would
            # then shift every field after it. Joined on \x1f, which is not IFS
            # whitespace and which Tsv.clean() strips out of every value.
            IFS=$'\x1f' read -r id name done_ expected errors <<< "$(printf '%s' "$line" \
                | awk -F'\t' -v OFS="$SEP" '{print $1, $2, $11, $12, $13}')"
            # Only the channels this run touched. Another operator's deploy
            # converging at the same time is not this pipeline's business.
            local wanted=false item
            for item in "${CHANGED_CHANNELS[@]}"; do
                [[ "$item" == "$id" ]] && wanted=true && break
            done
            [[ "$wanted" == "true" ]] || continue

            if (( ${errors:-0} > 0 )); then
                failed=$((failed + 1))
                report+=("${name}: failed on ${errors} node(s)")
            elif [[ "${done_:-0}" != "${expected:-0}" || "${expected:-0}" == "0" ]]; then
                pending=$((pending + 1))
                report+=("${name}: ${done_:-0}/${expected:-0} nodes")
            fi
        done < <(printf '%s' "$rows_xml" | tr '<' '\n' \
            | sed -n 's|^string>\(.*\)|\1|p')

        if (( failed > 0 )); then
            # A node that cannot deploy the channel will not start being able to
            # by being waited for, so this fails immediately rather than at the
            # timeout. The cluster view names the node and carries its error.
            for line in "${report[@]}"; do
                fail "$line"
            done
            fail "see the Cluster view in the web console for the node and the error"
            return 1
        fi

        if (( pending == 0 )); then
            ok "every live node has deployed all ${#CHANGED_CHANNELS[@]} changed channel(s)"
            return 0
        fi

        if (( SECONDS >= deadline )); then
            for line in "${report[@]}"; do
                fail "$line"
            done
            fail "gave up after ${timeout}s waiting for the cluster to converge"
            return 1
        fi

        if [[ "$first" == "true" ]]; then
            skip "waiting for ${pending} channel(s) to reach every node"
            first=false
        fi
        sleep 3
    done
}

# request <METHOD> <path> [@file] -- wraps oie_api with dry-run and reporting.
request() {
    local method="$1" path="$2" body="${3:-}"
    if [[ "$DRY_RUN" == "true" ]]; then
        skip "would ${method} ${path}"
        return 0
    fi
    local out
    if out="$(oie_api "$method" "$path" "$body")"; then
        return 0
    fi
    fail "${method} ${path} -> HTTP $(oie_last_status)"
    [[ -n "$out" ]] && printf '        %s\n' "${out:0:500}" >&2
    return 1
}

########################################################################
# Connect
########################################################################
step "Connecting to ${OIE_URL}"
oie_wait_ready "${OIE_WAIT_TIMEOUT:-180}"

server_version="$(oie_api GET /server/version || true)"
ok "engine ${server_version:-unknown}, authenticating as ${OIE_USER}"

# Prove the credentials work before making any changes, so a bad password
# fails the job cleanly instead of half-applying the configuration.
if ! oie_api GET /users/current >/dev/null; then
    printf '%sauthentication failed (HTTP %s) for user %s%s\n' \
        "$C_RED" "$(oie_last_status)" "$OIE_USER" "$C_RESET" >&2
    exit 1
fi

########################################################################
# 1. Code template libraries
########################################################################
libraries_file="${CONFIG_DIR}/code-templates/libraries.xml"
if [[ -f "$libraries_file" ]]; then
    step "Code template libraries"
    request PUT "/codeTemplateLibraries?override=true" "@${libraries_file}" \
        && ok "$(basename "$libraries_file")"
else
    step "Code template libraries"
    skip "no ${libraries_file#"$ROOT/"}, skipping"
fi

########################################################################
# 2. Code templates
########################################################################
step "Code templates"
templates_dir="${CONFIG_DIR}/code-templates/templates"
if compgen -G "${templates_dir}/*.xml" >/dev/null; then
    for file in "${templates_dir}"/*.xml; do
        id="$(xpath_value '/codeTemplate/id' "$file")"
        name="$(xpath_value '/codeTemplate/name' "$file")"
        if [[ -z "$id" ]]; then
            fail "$(basename "$file"): no /codeTemplate/id"
            continue
        fi
        request PUT "/codeTemplates/${id}?override=true" "@${file}" \
            && ok "${name:-$id}"
    done
else
    skip "no templates in ${templates_dir#"$ROOT/"}"
fi

########################################################################
# 3. Channel groups
########################################################################
groups_file="${CONFIG_DIR}/channel-groups.xml"
step "Channel groups"
if [[ -f "$groups_file" ]]; then
    # _bulkUpdate is multipart/form-data, so it bypasses oie_api's XML body
    # handling and is built here directly.
    if [[ "$DRY_RUN" == "true" ]]; then
        skip "would POST /channelgroups/_bulkUpdate"
    else
        _oie_build_args
        if curl "${oie_curl_args[@]}" --fail \
                --request POST \
                --form "channelGroups=@${groups_file};type=application/xml" \
                --form "removedChannelGroupIds=<list/>;type=application/xml" \
                "${OIE_URL}/channelgroups/_bulkUpdate?override=true" >/dev/null; then
            ok "$(basename "$groups_file")"
        else
            fail "POST /channelgroups/_bulkUpdate"
        fi
    fi
else
    skip "no ${groups_file#"$ROOT/"}, skipping"
fi

########################################################################
# 4. Channels
########################################################################
step "Channels"
channels_dir="${CONFIG_DIR}/channels"

# One request tells us what already exists, which keeps the create/update
# decision deterministic instead of relying on how the API reports a miss.
existing_ids_xml="$(oie_api GET /channels/idsAndNames || true)"
declare -A EXISTING=()
while IFS= read -r id; do
    [[ -n "$id" ]] && EXISTING["$id"]=1
done < <(printf '%s' "$existing_ids_xml" \
    | tr '<' '\n' | sed -n 's|^string>\(.*\)|\1|p' | awk 'NR % 2 == 1')

declare -A DESIRED=()

if compgen -G "${channels_dir}/*.xml" >/dev/null; then
    for file in "${channels_dir}"/*.xml; do
        id="$(xpath_value '/channel/id' "$file")"
        name="$(xpath_value '/channel/name' "$file")"
        if [[ -z "$id" ]]; then
            fail "$(basename "$file"): no /channel/id -- export it from the Administrator"
            continue
        fi
        DESIRED["$id"]="${name:-$id}"

        if [[ -n "${EXISTING[$id]:-}" ]]; then
            # override=true wins against the revision counter the Administrator
            # bumps. Git is the source of truth here, not the server.
            if request PUT "/channels/${id}?override=true" "@${file}"                 && [[ "$DRY_RUN" != "true" ]]; then
                verify_channel_valid "$id" "${name:-$id}" && ok "updated ${name:-$id}"
            fi
        else
            if request POST "/channels" "@${file}"                 && [[ "$DRY_RUN" != "true" ]]; then
                verify_channel_valid "$id" "${name:-$id}" && ok "created ${name:-$id}"
            fi
        fi
    done
else
    skip "no channels in ${channels_dir#"$ROOT/"}"
fi

########################################################################
# 5. Configuration map
########################################################################
step "Configuration map"
map_file="${CONFIG_DIR}/configuration-map.properties"
if [[ -f "$map_file" ]]; then
    # The API takes Map<String, ConfigurationProperty>, which XStream renders
    # with the fully qualified class name as the value element. Build that from
    # a plain properties file so the tracked artefact stays readable and
    # merge-friendly.
    #
    # ${VAR} in a value is expanded from the environment, which is how CI
    # injects per-environment endpoints and secrets without committing them.
    tmp_map="$(mktemp)"
    oie_cleanup_add "$tmp_map"

    # XML-escape via sed rather than ${var//&/&amp;}: since bash 5.2 a bare &
    # in a parameter-substitution replacement stands for the matched text, so
    # the pure-bash version silently produced "<lt;" instead of "&lt;". In sed
    # the intent is spelled out as \&. Ampersand must be substituted first.
    esc() {
        printf '%s' "$1" | sed -e 's/&/\&amp;/g' -e 's/</\&lt;/g' -e 's/>/\&gt;/g'
    }

    {
        printf '<map>\n'
        while IFS= read -r line || [[ -n "$line" ]]; do
            [[ "$line" =~ ^[[:space:]]*# ]] && continue
            [[ "$line" =~ ^[[:space:]]*$ ]] && continue
            [[ "$line" == *=* ]] || continue
            key="${line%%=*}"
            value="${line#*=}"
            key="${key#"${key%%[![:space:]]*}"}"; key="${key%"${key##*[![:space:]]}"}"
            value="${value#"${value%%[![:space:]]*}"}"; value="${value%"${value##*[![:space:]]}"}"

            # Expand ${ENV_VAR} references, leaving unset ones empty.
            while [[ "$value" =~ \$\{([A-Za-z_][A-Za-z0-9_]*)\} ]]; do
                var="${BASH_REMATCH[1]}"
                value="${value//\$\{$var\}/${!var:-}}"
            done

            printf '  <entry>\n'
            printf '    <string>%s</string>\n' "$(esc "$key")"
            printf '    <com.mirth.connect.util.ConfigurationProperty>\n'
            printf '      <value>%s</value>\n' "$(esc "$value")"
            printf '      <comment></comment>\n'
            printf '    </com.mirth.connect.util.ConfigurationProperty>\n'
            printf '  </entry>\n'
        done < "$map_file"
        printf '</map>\n'
    } > "$tmp_map"

    entries="$(grep -c '<entry>' "$tmp_map" || true)"
    request PUT "/server/configurationMap" "@${tmp_map}" \
        && ok "${entries} entries"
else
    skip "no ${map_file#"$ROOT/"}, skipping"
fi

########################################################################
# 6. Prune channels that are no longer in git
########################################################################
if [[ "$DO_PRUNE" == "true" ]]; then
    step "Pruning channels not present in config/"
    pruned=0
    for id in "${!EXISTING[@]}"; do
        [[ -n "${DESIRED[$id]:-}" ]] && continue
        warn "removing ${id}"
        request POST "/channels/${id}/_undeploy?returnErrors=false" || true
        request DELETE "/channels/${id}" && pruned=$((pruned + 1)) || true
    done
    (( pruned == 0 )) && skip "nothing to prune" || ok "removed ${pruned}"
fi

########################################################################
# 7. Deploy
########################################################################
if [[ "$DO_DEPLOY" == "true" ]]; then
    step "Deploying"
    if (( ${#CHANGED_CHANNELS[@]} == 0 )); then
        skip "no channels changed"
    else
        for id in "${CHANGED_CHANNELS[@]}"; do
            # returnErrors=true surfaces a deploy-time exception (a bad
            # transformer, a port already in use) as a non-2xx response instead
            # of a silent no-op.
            if request POST "/channels/${id}/_deploy?returnErrors=true"; then
                # || true so one bad channel does not abort the run under
                # set -e; every failure is counted and reported at the end.
                verify_channel_deployed "$id" "${DESIRED[$id]:-$id}" || true
            fi
        done
    fi
fi

########################################################################
# 8. Cluster convergence
########################################################################
if (( WAIT_CLUSTER > 0 )) && [[ "$DRY_RUN" != "true" ]]; then
    step "Cluster"
    if [[ "$DO_DEPLOY" != "true" ]]; then
        skip "nothing was deployed, so there is nothing to converge"
    elif (( ${#CHANGED_CHANNELS[@]} == 0 )); then
        skip "no channels changed"
    else
        # || true so the failure is counted and reported with everything else
        # rather than aborting the run under set -e.
        wait_for_cluster "$WAIT_CLUSTER" || true
    fi
fi

########################################################################
# Summary
########################################################################
printf '\n'
if (( FAILURES > 0 )); then
    printf '%s%d step(s) failed%s\n' "$C_RED" "$FAILURES" "$C_RESET" >&2
    exit 1
fi
if [[ "$DRY_RUN" == "true" ]]; then
    printf '%sdry run complete, nothing was changed%s\n' "$C_DIM" "$C_RESET"
else
    printf '%sconfiguration pushed successfully%s\n' "$C_GREEN" "$C_RESET"
fi
