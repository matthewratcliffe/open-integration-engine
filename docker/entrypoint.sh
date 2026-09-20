#!/usr/bin/env bash
#
# Renders /opt/engine/conf from the environment, then execs the engine.
#
# The environment contract is deliberately the same as the upstream official
# image's `configure-from-env` (DATABASE*, KEYSTORE_*, SESSION_STORE, SERVER_ID,
# _MP_*, VMOPTIONS, /run/secrets/mirth_properties, custom-extensions,
# *_DOWNLOAD, DELAY) so this image is a drop-in replacement in either
# direction. Differences, all deliberate:
#
#   * conf/ and extensions/ are rebuilt from the pristine copies baked into the
#     image on every boot, so a restart can never inherit half-applied config
#     from the previous one. Safe to do: the DB schema version lives in the
#     SCHEMA_INFO table, not in mirth.properties, so resetting the file does not
#     confuse the migrator.
#   * Property values are substituted with awk via the environment rather than
#     sed, so passwords containing / & or backslashes cannot corrupt the file.
#   * Any FOO_FILE variable seeds FOO from a file (Docker/GitLab/K8s secrets).
#   * Optionally blocks until the database TCP port accepts connections.

set -euo pipefail

APP_DIR=/opt/engine
CONF="${APP_DIR}/conf"
CONF_DIST="${APP_DIR}/conf.dist"
PROPS="${CONF}/mirth.properties"
VMOPTS="${CONF}/custom.vmoptions"

log() { printf '%s [entrypoint] %s\n' "$(date -u '+%Y-%m-%d %H:%M:%S')" "$*"; }
die() { log "ERROR: $*"; exit 1; }

########################################################################
# Secrets: FOO_FILE -> FOO
########################################################################
while IFS= read -r name; do
    case "$name" in
        *_FILE) ;;
        *) continue ;;
    esac
    target="${name%_FILE}"
    [[ -n "$target" ]] || continue
    # An explicitly set FOO always wins over FOO_FILE.
    [[ -n "${!target:-}" ]] && continue
    path="${!name}"
    [[ -r "$path" ]] || die "${name} points at ${path}, which is not readable"
    value="$(<"$path")"
    export "${target}=${value}"
    log "loaded ${target} from ${path}"
done < <(compgen -e)

########################################################################
# Rebuild conf/ and extensions/ from the image's pristine copies
########################################################################
if [[ "${OIE_CONF_RESET:-true}" == "true" ]]; then
    if [[ -w "$CONF" && ( ! -e "$PROPS" || -w "$PROPS" ) ]]; then
        cp -a "${CONF_DIST}/." "${CONF}/"
        log "conf/ reset from image defaults"
    else
        log "WARNING: conf/ is not writable, keeping it as mounted"
    fi

    if [[ -d "${APP_DIR}/extensions.dist" && -w "${APP_DIR}" ]]; then
        rm -rf "${APP_DIR}/extensions"
        cp -a "${APP_DIR}/extensions.dist" "${APP_DIR}/extensions"
        log "extensions/ reset to the bundled set"
    fi

    # Clear deployed WARs for the same reason.
    #
    # MirthWebServer scans webapps/ for *.war once at startup. An extension that
    # serves a web UI (the OIE web administrator's Web Support plugin, for
    # example) copies its WAR in from its own ServicePlugin.init(), which runs on
    # every engine start and before that scan -- so clearing here cannot strand
    # the UI, and it does stop a WAR from an extension you removed or downgraded
    # continuing to be served. Upstream warns that exact WAR can be left behind
    # if the engine is killed mid-uninstall.
    if [[ -d "${APP_DIR}/webapps" && -w "${APP_DIR}/webapps" ]]; then
        if compgen -G "${APP_DIR}/webapps/*.war" > /dev/null; then
            rm -f "${APP_DIR}"/webapps/*.war
            log "webapps/ cleared; extensions redeploy their own WARs at startup"
        fi
    else
        mkdir -p "${APP_DIR}/webapps" 2>/dev/null || true
    fi
fi

########################################################################
# Property helpers
########################################################################

# set_prop <key> <value> -- replaces the first assignment of <key>, drops any
# later duplicates, appends if absent. The value travels through the
# environment, so no character in it is special to the rewriter.
set_prop() {
    local key="$1" value="$2"
    if [[ ! -w "$PROPS" ]]; then
        log "WARNING: cannot set ${key}, ${PROPS} is read-only"
        return 0
    fi
    _SP_KEY="$key" _SP_VAL="$value" awk '
        BEGIN {
            key = ENVIRON["_SP_KEY"]; val = ENVIRON["_SP_VAL"]
            re = key; gsub(/\./, "\\.", re)
            re = "^[ \t]*" re "[ \t]*="
            written = 0
        }
        $0 ~ re { if (!written) { print key " = " val; written = 1 } next }
        { print }
        END { if (!written) print key " = " val }
    ' "$PROPS" > "${PROPS}.new"
    mv "${PROPS}.new" "$PROPS"
}

# set_prop_from_env <key> <ENV_NAME> -- only touches the file when the variable
# is set, so an empty value is still an intentional override (server.url = ).
set_prop_from_env() {
    local key="$1" env_name="$2"
    [[ -z "${!env_name+x}" ]] && return 0
    set_prop "$key" "${!env_name}"
}

########################################################################
# Known environment variables -> mirth.properties
########################################################################
set_prop_from_env database                                    DATABASE
set_prop_from_env database.url                                DATABASE_URL
set_prop_from_env database.username                           DATABASE_USERNAME
set_prop_from_env database.password                           DATABASE_PASSWORD
set_prop_from_env database.driver                             DATABASE_DRIVER
set_prop_from_env database.max-connections                    DATABASE_MAX_CONNECTIONS
set_prop_from_env database-readonly.max-connections           DATABASE_READONLY_MAX_CONNECTIONS
set_prop_from_env database.enable-read-write-split            DATABASE_ENABLE_READ_WRITE_SPLIT
set_prop_from_env database.connection.maxretry                DATABASE_MAX_RETRY
set_prop_from_env database.connection.retrywaitinmilliseconds DATABASE_RETRY_WAIT

set_prop_from_env keystore.storepass                          KEYSTORE_STOREPASS
set_prop_from_env keystore.keypass                            KEYSTORE_KEYPASS
set_prop_from_env keystore.type                               KEYSTORE_TYPE

set_prop_from_env http.port                                   HTTP_PORT
set_prop_from_env https.port                                  HTTPS_PORT
set_prop_from_env server.url                                  SERVER_URL
set_prop_from_env server.api.sessionstore                     SESSION_STORE
set_prop_from_env server.api.require-requested-with           API_REQUIRE_REQUESTED_WITH
set_prop_from_env server.api.accesscontrolalloworigin         API_CORS_ALLOW_ORIGIN
set_prop_from_env server.startupdeploy                        SERVER_STARTUP_DEPLOY

# server.id, and why a clustered pod must not generate its own
#
# The engine writes a random server.id into appdata/server.id on first boot and
# stamps it onto every message, connector message and statistics row it writes.
# Queues are scoped by it: Donkey's recovery and queue-rotation queries all
# filter on SERVER_ID, so a node's queued messages are picked up by the node
# that owns them and by nothing else.
#
# In a cluster that makes the id part of the deployment rather than of the
# container. A pod that generates a fresh one after being rescheduled abandons
# whatever it had queued -- the messages stay in the tables under an id nothing
# answers to. So derive it from the pod's stable name instead, and it survives
# rescheduling, image changes and an emptyDir appdata.
#
# OIE_CLUSTER_NAME is in the input so two clusters sharing a naming convention
# (both with an "engine-0") cannot collide if they are ever pointed at one
# database.
# The name this node is derived from: an explicit override, else what the node
# calls itself in the cluster, else the container's hostname.
#
# OIE_CLUSTER_NODE_NAME is in that list because HOSTNAME is a poor last resort in
# compose, where it is the container id and therefore changes on every recreate.
# In Kubernetes all three are the pod name and the order does not matter.
server_id_source() {
    if [[ -n "${OIE_SERVER_ID_FROM:-}" ]]; then
        printf '%s' "$OIE_SERVER_ID_FROM"
    elif [[ -n "${OIE_CLUSTER_NODE_NAME:-}" ]]; then
        printf '%s' "$OIE_CLUSTER_NODE_NAME"
    else
        printf '%s' "${HOSTNAME:-}"
    fi
}

derive_server_id() {
    [[ -z "${SERVER_ID:-}" ]] || return 0
    [[ -n "$(server_id_source)" ]] || return 0
    [[ "${OIE_CLUSTER_ENABLED:-false}" == "true" ]] || return 0

    # Never take an identity away from an engine that already has one. An
    # existing appdata/server.id is stamped on every message that engine has
    # already written and on everything it has queued; replacing it would strand
    # all of it under an id nothing answers to any more. Deriving is for a pod
    # with an empty appdata, which is the Kubernetes case -- and there the
    # derived value is the same on every boot anyway.
    if [[ -s "${APP_DIR}/appdata/server.id" ]]; then
        log "keeping the existing server.id in appdata"
        return 0
    fi

    local name
    name="$(server_id_source)"

    # OIE_SERVER_ID_STYLE=name makes the id the node's name instead of a UUID,
    # and it is worth understanding what that buys.
    #
    # Every message row and every connector message row carries SERVER_ID, and
    # both administrators can show it as a column -- in the web console it is
    # "Server Id" in the message browser's column menu, on the message and on
    # each destination. With the default UUID that column answers "which node"
    # with something nobody can read; with a name it says `oie-worker-1`, on
    # every message, with no plugin and no console change.
    #
    # Nothing in the engine requires the id to be a UUID: getServerId() is a
    # String and the column is VARCHAR(36), which is why the value is sanitised
    # and capped here. Two clusters sharing one database would need the names to
    # be unique across both -- the UUID style folds the cluster name in for
    # exactly that case, and this one does not.
    if [[ "${OIE_SERVER_ID_STYLE:-uuid}" == "name" ]]; then
        # Anything outside [A-Za-z0-9._-] becomes a dash, and 36 characters is
        # the width of the column it has to fit.
        SERVER_ID="$(printf '%s' "$name" | tr -c 'A-Za-z0-9._-' '-' | cut -c1-36)"
        export SERVER_ID
        log "derived server.id '${SERVER_ID}' from the node name"
        return 0
    fi

    local seed="${OIE_CLUSTER_NAME:-default}/${name}"
    local hex
    hex="$(printf '%s' "$seed" | sha1sum | cut -c1-32)"
    # Formatted as a UUID, with the version and variant nibbles forced, so it
    # looks like every other server.id in the database and fits the 36-character
    # column. Deterministic: the same pod name always yields the same id.
    SERVER_ID="${hex:0:8}-${hex:8:4}-5${hex:13:3}-8${hex:17:3}-${hex:20:12}"
    export SERVER_ID
    log "derived server.id from '${seed}'"
}
derive_server_id

if [[ -n "${SERVER_ID:-}" ]]; then
    printf 'server.id = %s\n' "$SERVER_ID" > "${APP_DIR}/appdata/server.id"
    log "pinned server.id"
fi

# Jetty 9.4's DefaultSessionIdManager names every node "node0" unless
# JETTY_WORKER_INSTANCE says otherwise, and appends that name to each session
# id. With the session store in the database (SESSION_STORE=true) a cluster
# works either way, but identical worker names make every session id look like
# it came from the same node -- which is a poor position to debug a session
# problem from, and stops a load balancer doing affinity if you ever want it.
if [[ -z "${JETTY_WORKER_INSTANCE:-}" && -n "${HOSTNAME:-}" ]]; then
    # Jetty rejects a worker name containing a dot.
    export JETTY_WORKER_INSTANCE="${HOSTNAME//./-}"
fi

########################################################################
# _MP_* passthrough (upstream convention: __ -> '-', _ -> '.', lowercased)
#   _MP_SERVER_INITIALADMINPASSWORD -> server.initialadminpassword
#   _MP_DATABASE_MAX__CONNECTIONS   -> database.max-connections
########################################################################
while IFS= read -r name; do
    case "$name" in
        _MP_*) ;;
        *) continue ;;
    esac
    key="${name:4}"
    key="${key//__/-}"
    key="${key//_/.}"
    key="${key,,}"
    set_prop "$key" "${!name}"
    log "set ${key} from ${name}"
done < <(compgen -e)

########################################################################
# Whole-file secret merge: /run/secrets/mirth_properties
########################################################################
if [[ -f /run/secrets/mirth_properties ]]; then
    log "merging /run/secrets/mirth_properties"
    while IFS= read -r line || [[ -n "$line" ]]; do
        [[ "$line" =~ ^[[:space:]]*# ]] && continue
        [[ "$line" =~ ^[[:space:]]*$ ]] && continue
        [[ "$line" == *=* ]] || continue
        key="${line%%=*}"
        value="${line#*=}"
        key="${key#"${key%%[![:space:]]*}"}"
        key="${key%"${key##*[![:space:]]}"}"
        value="${value#"${value%%[![:space:]]*}"}"
        value="${value%"${value##*[![:space:]]}"}"
        [[ -n "$key" ]] && set_prop "$key" "$value"
    done < /run/secrets/mirth_properties
fi

########################################################################
# JVM options -> conf/custom.vmoptions
#
# oieserver.vmoptions includes base_includes, then default_modules, then
# custom.vmoptions, and the install4j launcher applies includes in that order,
# so -Xmx here overrides the -Xmx256m default rather than fighting it.
########################################################################
if [[ -w "$CONF" ]]; then
    {
        if [[ -n "${OIE_HEAP_MIN:-}" ]]; then printf -- '-Xms%s\n' "$OIE_HEAP_MIN"; fi
        if [[ -n "${OIE_HEAP_MAX:-}" ]]; then printf -- '-Xmx%s\n' "$OIE_HEAP_MAX"; fi
        if [[ -n "${VMOPTIONS:-}" ]]; then
            IFS=',' read -ra _opts <<< "$VMOPTIONS"
            for opt in "${_opts[@]}"; do
                opt="${opt#"${opt%%[![:space:]]*}"}"
                opt="${opt%"${opt##*[![:space:]]}"}"
                [[ -n "$opt" ]] && printf '%s\n' "$opt"
            done
        fi
    } >> "$VMOPTS"

    if [[ -f /run/secrets/oieserver_vmoptions ]]; then
        cat /run/secrets/oieserver_vmoptions >> "$VMOPTS"
        printf '\n' >> "$VMOPTS"
        log "merged /run/secrets/oieserver_vmoptions"
    fi
fi

########################################################################
# Downloads: extensions, custom jars, keystore
########################################################################
curl_opts=(--fail --silent --show-error --location --retry 3)
if [[ "${ALLOW_INSECURE:-false}" == "true" ]]; then
    curl_opts+=(--insecure)
fi
if [[ -n "${OIE_DOWNLOAD_HEADER:-}" ]]; then
    curl_opts+=(--header "$OIE_DOWNLOAD_HEADER")
fi

install_extension_zip() {
    local zip="$1"
    unzip -o -q "$zip" -d "${APP_DIR}/extensions" \
        || die "failed to unpack extension ${zip}"
    log "installed extension $(basename "$zip")"
}

# retag_installed_extensions
#
# ExtensionLoader.isExtensionCompatible() compares the server version against
# the extension's <mirthVersion> with an exact string match over a
# comma-separated list -- not a range. So an extension built for Mirth Connect
# 4.5.2 is refused outright by OIE 4.6.0:
#
#   ERROR ExtensionLoader: Extension "..." is not compatible with this version
#   of Open Integration Engine and was not loaded.
#
# When OIE_EXTENSION_RETAG_VERSION=true, rewrite <mirthVersion> in the metadata
# of the extensions we installed (never the bundled ones, which are already
# correct) to the running version. That is the difference between a licensed
# extension loading and being ignored.
#
# Off by default and deliberately loud: it asserts a compatibility claim the
# vendor has not made. Prefer a build from the vendor that declares this
# version, and check with them before relying on a retagged one in production.
retag_installed_extensions() {
    [[ "${OIE_EXTENSION_RETAG_VERSION:-false}" == "true" ]] || return 0

    local version="${OIE_VERSION:-}"
    if [[ -z "$version" ]]; then
        version="$(awk -F= '/^[[:space:]]*version[[:space:]]*=/ {gsub(/[[:space:]]/, "", $2); print $2; exit}' "$PROPS")"
    fi
    if [[ -z "$version" ]]; then
        log "WARNING: cannot determine the server version, not retagging extensions"
        return 0
    fi

    local dir name meta file current
    for dir in "${APP_DIR}"/extensions/*/; do
        [[ -d "$dir" ]] || continue
        name="$(basename "$dir")"
        # Bundled extensions ship with the right version already.
        [[ -d "${APP_DIR}/extensions.dist/${name}" ]] && continue

        for meta in plugin.xml source.xml destination.xml; do
            file="${dir}${meta}"
            [[ -f "$file" ]] || continue

            current=""
            if [[ "$(cat "$file")" =~ \<mirthVersion\>([^\<]*)\</mirthVersion\> ]]; then
                current="${BASH_REMATCH[1]}"
            fi
            [[ "$current" == "$version" ]] && continue

            sed -i "s|<mirthVersion>[^<]*</mirthVersion>|<mirthVersion>${version}</mirthVersion>|" "$file"
            log "RETAGGED ${name}/${meta}: mirthVersion '${current}' -> '${version}'"
        done
    done
}

# Individual extension zip URLs -- the practical way to pull a licensed
# extension (e.g. the Zen SSL extension) from a private package registry:
#   OIE_EXTENSION_URLS=https://gitlab.example.com/api/v4/projects/1/packages/generic/zen/1.0/zen.zip
#   OIE_DOWNLOAD_HEADER=PRIVATE-TOKEN: <token>
#
# It is also how a third-party extension (the OIE web administrator's Web
# Support plugin, Sentinel, TLS Manager) gets installed without committing a
# binary to this repo. An entry may carry a checksum, which is then enforced:
#
#   OIE_EXTENSION_URLS=sha256:11014e5f...@https://github.com/o/r/releases/download/v1.0.3/websupport-1.0.3.zip
#
# Pin it for anything fetched from outside your own infrastructure. These
# archives hold jars and WARs the engine loads into its own JVM, so an artifact
# that changes under you is arbitrary code execution inside the engine.
# Releases generally publish SHA256SUMS next to the zip.
#
# Downloads are cached under appdata/ (a persistent volume), and a failed
# download falls back to the cached copy. Without that, every restart depends on
# the network: one transient DNS blip and the engine cannot start at all, which
# turns a five-second outage into an outage of the engine. A pinned checksum is
# still enforced against the cached copy, so the fallback cannot serve something
# other than what you asked for.
if [[ -n "${OIE_EXTENSION_URLS:-}" ]]; then
    tmp="$(mktemp -d)"
    cache="${APP_DIR}/appdata/extension-cache"
    mkdir -p "$cache" 2>/dev/null || true

    idx=0
    for entry in ${OIE_EXTENSION_URLS//,/ }; do
        [[ -n "$entry" ]] || continue
        idx=$((idx + 1))

        expected=""
        url="$entry"
        if [[ "$entry" == sha256:* ]]; then
            # sha256:<hex>@<url>, split on the first @ only so an @ inside the
            # URL itself survives.
            expected="${entry#sha256:}"
            expected="${expected%%@*}"
            url="${entry#sha256:"${expected}"@}"
        fi

        # Key the cache on the checksum when pinned (content-addressed, so a
        # version bump is a different entry), otherwise on the URL's basename.
        if [[ -n "$expected" ]]; then
            cached="${cache}/${expected}.zip"
        else
            base="${url##*/}"
            base="${base%%\?*}"
            cached="${cache}/${base:-ext-${idx}.zip}"
        fi

        staged="${tmp}/ext-${idx}.zip"
        source_desc=""

        log "downloading extension ${idx} from ${url%%\?*}"
        if curl "${curl_opts[@]}" -o "$staged" "$url"; then
            source_desc="download"
        elif [[ -f "$cached" ]]; then
            log "WARNING: download failed for ${url%%\?*}, using cached copy"
            cp "$cached" "$staged"
            source_desc="cache"
        else
            die "extension download failed and nothing cached: ${url%%\?*}"
        fi

        if [[ -n "$expected" ]]; then
            actual="$(sha256sum "$staged" | cut -d' ' -f1)"
            [[ "$actual" == "$expected" ]] \
                || die "checksum mismatch for ${url%%\?*} (from ${source_desc}): expected ${expected}, got ${actual}"
            log "checksum verified for extension ${idx} (from ${source_desc})"
        else
            log "WARNING: no checksum pinned for ${url%%\?*}"
        fi

        # Refresh the cache only from a verified download.
        if [[ "$source_desc" == "download" ]]; then
            if cp "$staged" "${cached}.tmp" 2>/dev/null && mv "${cached}.tmp" "$cached" 2>/dev/null; then
                :
            else
                log "WARNING: could not cache ${url##*/}"
            fi
        fi

        install_extension_zip "$staged"
    done
    rm -rf "$tmp"
fi

# A single zip-of-zips, as the upstream official image expects.
if [[ -n "${EXTENSIONS_DOWNLOAD:-}" ]]; then
    tmp="$(mktemp -d)"
    log "downloading extension bundle from ${EXTENSIONS_DOWNLOAD%%\?*}"
    curl "${curl_opts[@]}" -o "${tmp}/bundle.zip" "$EXTENSIONS_DOWNLOAD" \
        || die "extension bundle download failed"
    unzip -o -q "${tmp}/bundle.zip" -d "${tmp}/unpacked"
    while IFS= read -r -d '' zip; do
        install_extension_zip "$zip"
    done < <(find "${tmp}/unpacked" -name '*.zip' -print0)
    rm -rf "$tmp"
fi

# Mounted zips: compose maps ./extensions here read-only.
if compgen -G "${APP_DIR}/custom-extensions/*.zip" > /dev/null; then
    for zip in "${APP_DIR}"/custom-extensions/*.zip; do
        install_extension_zip "$zip"
    done
fi

# Overlay files onto installed extensions.
#
# For adding what a vendor's zip does not contain -- most usefully a `webadmin/`
# folder, which is how the OIE web console discovers a plugin's UI. An extension
# that ships only a standalone WAR (TLS Manager) has no console half, so it never
# appears in the sidebar until someone supplies one.
#
# Applied after the zips are unpacked and re-applied on every boot, so it
# survives the extensions/ reset. Each top-level directory under the overlay is
# an extension name, and it must already exist -- overlaying onto an extension
# that is not installed is a silent no-op otherwise.
apply_extension_overlay() {
    local overlay="${APP_DIR}/custom-extensions/webadmin-overlay"
    [[ -d "$overlay" ]] || return 0

    local dir name
    for dir in "${overlay}"/*/; do
        [[ -d "$dir" ]] || continue
        name="$(basename "$dir")"
        if [[ ! -d "${APP_DIR}/extensions/${name}" ]]; then
            log "WARNING: overlay for '${name}' skipped, that extension is not installed"
            continue
        fi
        cp -a "${dir}." "${APP_DIR}/extensions/${name}/"
        log "applied overlay to extension '${name}'"
    done
}
apply_extension_overlay

# After every install path above, so URL-fetched and mounted zips are both covered.
retag_installed_extensions

# OIE_DISABLE_EXTENSIONS: remove named extensions after everything is installed.
#
# What it is for is running the same image in two roles. Several extensions do
# scheduled work from settings they read out of the shared configuration table
# -- the engine's own data pruner, this repo's git sync and volume monitor,
# Sentinel -- and in a cluster every pod that has them installed does that work
# on the same schedule, against the same database, three or four times over.
# Their settings cannot differ per node, because the settings are shared. So the
# installation is what differs per node:
#
#   worker pods:  OIE_DISABLE_EXTENSIONS=datapruner,gitsync,volumemonitor,sentinel
#   utility pod:  (unset)
#
# Names are extension directory names, which are what `oie-check-extensions.sh`
# lists and what plugin.xml's `path` attribute declares. Removing something that
# is not there is not an error -- a list shared between environments should not
# fail on the one that never installed Sentinel -- but it is logged, because a
# typo here silently leaves a scheduler running everywhere.
disable_extensions() {
    [[ -n "${OIE_DISABLE_EXTENSIONS:-}" ]] || return 0

    local name
    for name in ${OIE_DISABLE_EXTENSIONS//,/ }; do
        name="$(printf '%s' "$name" | tr -d '[:space:]')"
        [[ -n "$name" ]] || continue
        # Never let a path escape the extensions directory.
        case "$name" in
            */*|..*) log "WARNING: ignoring invalid extension name '${name}'"; continue ;;
        esac
        if [[ -d "${APP_DIR}/extensions/${name}" ]]; then
            rm -rf "${APP_DIR:?}/extensions/${name}"
            log "disabled extension '${name}'"
        else
            log "WARNING: extension '${name}' is not installed, nothing to disable"
        fi
    done
}
disable_extensions

########################################################################
# Extension logging
#
# The shipped log4j2.properties sets `rootLogger = ERROR`, and a logger only
# gets through if its own level allows it -- so an extension logging at WARN or
# INFO is discarded unless its package is named. That is fine for libraries and
# wrong for the extensions this repo builds: they log operational facts a person
# is meant to read, such as a channel falling below its expected message volume
# or a scheduled pull being refused. Without this, those lines went nowhere and
# the plugin looked silent.
#
# Declaring only the level means additivity carries the records to the root
# appenders (stdout and the log file), which is how the engine's own
# com.mirth.connect.server.Mirth logger works alongside an ERROR root.
########################################################################
configure_extension_logging() {
    local level="${OIE_PLUGIN_LOG_LEVEL:-INFO}"
    local cfg="${APP_DIR}/conf/log4j2.properties"

    [[ -f "$cfg" ]] || return 0
    if [[ "$level" == "OFF" ]]; then
        log "extension logging left at the root level (OIE_PLUGIN_LOG_LEVEL=OFF)"
        return 0
    fi
    # conf/ is reset from conf.dist on every boot, so this is always a fresh
    # file; the guard is for the case where someone mounts their own conf/.
    if grep -q '^logger\.oiePlugins\.name' "$cfg"; then
        log "extension logging already configured in conf/log4j2.properties"
        return 0
    fi

    cat >>"$cfg" <<LOG4J

# Added by the container entrypoint; set OIE_PLUGIN_LOG_LEVEL to change or OFF
# to skip. Extensions built in this repository log under this package -- the
# whole of it, not just .plugins, because connectors built here live under
# org.openintegrationengine.connectors and log the same kind of operational
# fact (a FHIR listener refusing a port, say).
logger.oiePlugins.name = org.openintegrationengine
logger.oiePlugins.level = ${level}
LOG4J
    log "extension logging at ${level} for org.openintegrationengine"
}
configure_extension_logging

if [[ -n "${CUSTOM_JARS_DOWNLOAD:-}" ]]; then
    tmp="$(mktemp -d)"
    log "downloading custom jars from ${CUSTOM_JARS_DOWNLOAD%%\?*}"
    curl "${curl_opts[@]}" -o "${tmp}/jars.zip" "$CUSTOM_JARS_DOWNLOAD" \
        || die "custom jar download failed"
    unzip -o -q "${tmp}/jars.zip" -d "${APP_DIR}/server-launcher-lib"
    rm -rf "$tmp"
fi

if [[ -n "${KEYSTORE_DOWNLOAD:-}" ]]; then
    log "downloading keystore from ${KEYSTORE_DOWNLOAD%%\?*}"
    curl "${curl_opts[@]}" -o "${APP_DIR}/appdata/keystore.jks" "$KEYSTORE_DOWNLOAD" \
        || die "keystore download failed"
fi

# KEYSTORE_SOURCE: the same thing from a mounted file rather than a URL, which
# is what a Kubernetes Secret or a compose secret gives you.
#
# This is the one piece of shared state a second engine cannot do without.
# appdata/keystore.jks holds the engine's TLS certificate *and* the secret key
# its Encryptor uses -- DefaultConfigurationController.configureEncryption()
# loads or creates it there on first boot. Two engines with different keystores
# cannot read each other's encrypted channel properties or message content, and
# there is no recovering from having written data under a key that has since
# been replaced. So: generate one, keep it in a secret, and give every pod the
# same one.
#
# Copied rather than mounted in place because the engine writes to the keystore
# (it adds its generated certificate on first boot), and a Secret volume is
# read-only.
if [[ -n "${KEYSTORE_SOURCE:-}" ]]; then
    [[ -r "$KEYSTORE_SOURCE" ]] || die "KEYSTORE_SOURCE ${KEYSTORE_SOURCE} is not readable"
    if cmp -s "$KEYSTORE_SOURCE" "${APP_DIR}/appdata/keystore.jks" 2>/dev/null; then
        log "keystore already matches ${KEYSTORE_SOURCE}"
    else
        cp "$KEYSTORE_SOURCE" "${APP_DIR}/appdata/keystore.jks" \
            || die "could not copy the keystore from ${KEYSTORE_SOURCE}"
        chmod 600 "${APP_DIR}/appdata/keystore.jks" 2>/dev/null || true
        log "keystore installed from ${KEYSTORE_SOURCE}"
    fi
fi

########################################################################
# Wait for the database
########################################################################
# depends_on: service_healthy covers compose; this covers Kubernetes, Nomad and
# anything else that starts containers in parallel. The engine's own
# database.connection.maxretry is the second line of defence.
wait_for_db() {
    local timeout="${OIE_DB_WAIT_TIMEOUT:-60}"
    [[ "$timeout" =~ ^[0-9]+$ ]] || return 0
    (( timeout > 0 )) || return 0

    local url="${DATABASE_URL:-}" host port
    # jdbc:postgresql://host:port/db  |  jdbc:postgresql://host/db
    [[ "$url" =~ ^jdbc:[a-z0-9]+://([^/:?]+)(:([0-9]+))? ]] || return 0
    host="${BASH_REMATCH[1]}"
    port="${BASH_REMATCH[3]:-5432}"

    log "waiting up to ${timeout}s for ${host}:${port}"
    local deadline=$(( SECONDS + timeout ))
    until (exec 3<>"/dev/tcp/${host}/${port}") 2>/dev/null; do
        if (( SECONDS >= deadline )); then
            log "WARNING: ${host}:${port} unreachable after ${timeout}s, starting anyway"
            return 0
        fi
        sleep 1
    done
    log "${host}:${port} is accepting connections"
}
wait_for_db

# Upstream compatibility: unconditional start delay.
if [[ -n "${DELAY:-}" ]]; then
    log "sleeping ${DELAY}s (DELAY)"
    sleep "$DELAY"
fi

log "starting Open Integration Engine ${OIE_VERSION:-} (database=${DATABASE:-derby})"
exec "$@"
