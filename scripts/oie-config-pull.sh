#!/usr/bin/env bash
#
# Exports a running engine's configuration into config/, so the git repo can be
# seeded from (or reconciled against) a server someone edited in the
# Administrator.
#
#   ./scripts/oie-config-pull.sh
#
# The result is exactly what oie-config-push.sh consumes, so the normal loop is:
#   pull -> review the diff -> commit -> let CI push it to the next environment.
#
# Channel XML is written one file per channel, named after the channel with the
# id kept inside the file. Renaming a channel therefore renames its file but
# keeps its identity.
#
# Requires: bash, curl, xmllint

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CONFIG_DIR="${OIE_CONFIG_DIR:-${ROOT}/config}"

# shellcheck source=scripts/oie-api.sh
source "${ROOT}/scripts/oie-api.sh"

command -v xmllint >/dev/null 2>&1 || {
    printf 'xmllint not found. Install libxml2-utils (Debian/Ubuntu or Alpine).\n' >&2
    exit 1
}

mkdir -p "${CONFIG_DIR}/channels" "${CONFIG_DIR}/code-templates/templates"

# slugify <name> -- a stable, filesystem-safe filename from a channel name.
slugify() {
    printf '%s' "$1" \
        | tr '[:upper:]' '[:lower:]' \
        | sed -e 's/[^a-z0-9]\+/-/g' -e 's/^-//' -e 's/-$//'
}

oie_wait_ready "${OIE_WAIT_TIMEOUT:-180}"

########################################################################
# Channels
########################################################################
printf '==> channels\n'
ids_xml="$(oie_api GET /channels/idsAndNames)"

# /channels/idsAndNames returns a map of <string>id</string><string>name</string>
# pairs; take the odd entries as ids.
mapfile -t ids < <(printf '%s' "$ids_xml" \
    | tr '<' '\n' | sed -n 's|^string>\(.*\)|\1|p' | awk 'NR % 2 == 1')

if (( ${#ids[@]} == 0 )); then
    printf '    no channels on the server\n'
else
    tmp="$(mktemp -d)"
    oie_cleanup_add "$tmp"
    for id in "${ids[@]}"; do
        [[ -n "$id" ]] || continue
        oie_api GET "/channels/${id}" > "${tmp}/channel.xml"
        name="$(xmllint --xpath 'string(/channel/name)' "${tmp}/channel.xml" 2>/dev/null || true)"
        slug="$(slugify "${name:-$id}")"
        [[ -n "$slug" ]] || slug="$id"
        # Reformat so the committed diff is line-oriented rather than one long line.
        xmllint --format "${tmp}/channel.xml" > "${CONFIG_DIR}/channels/${slug}.xml"
        printf '    %s -> config/channels/%s.xml\n' "${name:-$id}" "$slug"
    done
fi

########################################################################
# Code templates
########################################################################
printf '==> code template libraries\n'
if libs="$(oie_api GET /codeTemplateLibraries?includeCodeTemplates=false)"; then
    printf '%s' "$libs" | xmllint --format - > "${CONFIG_DIR}/code-templates/libraries.xml"
    printf '    config/code-templates/libraries.xml\n'
else
    printf '    none (HTTP %s)\n' "$(oie_last_status)"
fi

printf '==> code templates\n'
if templates="$(oie_api GET /codeTemplates)"; then
    tmp_t="$(mktemp)"
    printf '%s' "$templates" | xmllint --format - > "$tmp_t"
    count="$(xmllint --xpath 'count(/list/codeTemplate)' "$tmp_t" 2>/dev/null || echo 0)"
    for (( i = 1; i <= count; i++ )); do
        id="$(xmllint --xpath "string(/list/codeTemplate[$i]/id)" "$tmp_t")"
        name="$(xmllint --xpath "string(/list/codeTemplate[$i]/name)" "$tmp_t")"
        slug="$(slugify "${name:-$id}")"
        xmllint --xpath "/list/codeTemplate[$i]" "$tmp_t" \
            | xmllint --format - > "${CONFIG_DIR}/code-templates/templates/${slug}.xml"
        printf '    %s -> config/code-templates/templates/%s.xml\n' "${name:-$id}" "$slug"
    done
    rm -f "$tmp_t"
else
    printf '    none (HTTP %s)\n' "$(oie_last_status)"
fi

########################################################################
# Channel groups
########################################################################
printf '==> channel groups\n'
if groups="$(oie_api GET /channelgroups)"; then
    printf '%s' "$groups" | xmllint --format - > "${CONFIG_DIR}/channel-groups.xml"
    printf '    config/channel-groups.xml\n'
else
    printf '    none (HTTP %s)\n' "$(oie_last_status)"
fi

########################################################################
# Configuration map
########################################################################
printf '==> configuration map\n'
if map_xml="$(oie_api GET /server/configurationMap)"; then
    tmp_m="$(mktemp)"
    printf '%s' "$map_xml" | xmllint --format - > "$tmp_m"
    count="$(xmllint --xpath 'count(/map/entry)' "$tmp_m" 2>/dev/null || echo 0)"
    {
        printf '# Exported from %s on %s\n' "$OIE_URL" "$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
        printf '#\n# Values may reference ${ENV_VAR}; oie-config-push.sh expands them at\n'
        printf '# push time, which is how per-environment values stay out of git.\n\n'
        for (( i = 1; i <= count; i++ )); do
            k="$(xmllint --xpath "string(/map/entry[$i]/string)" "$tmp_m")"
            v="$(xmllint --xpath "string(/map/entry[$i]/*[2]/value)" "$tmp_m")"
            printf '%s = %s\n' "$k" "$v"
        done
    } > "${CONFIG_DIR}/configuration-map.properties"
    rm -f "$tmp_m"
    printf '    %s entries -> config/configuration-map.properties\n' "$count"
else
    printf '    none (HTTP %s)\n' "$(oie_last_status)"
fi

printf '\ndone -- review with: git diff config/\n'
