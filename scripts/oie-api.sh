#!/usr/bin/env bash
#
# Thin curl wrapper for the Open Integration Engine REST API.
#
# Source it, or call it directly:
#   ./scripts/oie-api.sh GET /server/version
#   ./scripts/oie-api.sh GET /channels/idsAndNames
#   ./scripts/oie-api.sh PUT /channels/<id>?override=true @config/channels/x.xml
#
# Environment:
#   OIE_URL        base URL including /api      (default https://localhost:8443/api)
#   OIE_USER       API user                     (default admin)
#   OIE_PASSWORD   API password                 (required)
#   OIE_INSECURE   true to skip TLS verification of the engine's self-signed
#                  cert. Fine against a container on the CI network; set
#                  OIE_CACERT instead when talking to a real front door.
#   OIE_CACERT     path to a CA bundle used to verify the engine/proxy
#   OIE_CLIENT_CERT / OIE_CLIENT_KEY
#                  client certificate to present, when the engine sits behind an
#                  mTLS proxy (see compose.proxy.yaml)
#   OIE_TIMEOUT    per-request timeout in seconds (default 60)

set -euo pipefail

OIE_URL="${OIE_URL:-https://localhost:8443/api}"
OIE_USER="${OIE_USER:-admin}"
OIE_TIMEOUT="${OIE_TIMEOUT:-60}"

# Builds the shared curl argument list in the global array `oie_curl_args`.
oie_curl_args=()
_oie_build_args() {
    : "${OIE_PASSWORD:?OIE_PASSWORD is not set}"

    oie_curl_args=(
        --silent
        --show-error
        --location
        --max-time "$OIE_TIMEOUT"
        # Write endpoints that return a bare boolean declare
        # @Produces({APPLICATION_JSON, TEXT_PLAIN}) and answer 406 to a bare
        # "Accept: application/xml". Listing XML first still gets XML from the
        # endpoints that can produce it, while */* keeps the rest working.
        #
        # Override with OIE_ACCEPT when a caller needs a specific
        # representation: curl sends duplicate Accept headers rather than
        # replacing, so adding one alongside this default does not work.
        --header "Accept: ${OIE_ACCEPT:-application/xml, application/json, text/plain, */*}"
        # HTTP Basic is accepted on every endpoint, which keeps CI stateless --
        # no /users/_login round trip and no JSESSIONID to carry.
        --user "${OIE_USER}:${OIE_PASSWORD}"
        # mirth.properties ships server.api.require-requested-with = true, which
        # rejects any request without this header as possible CSRF.
        --header "X-Requested-With: oie-config-push"
    )

    if [[ -n "${OIE_CACERT:-}" ]]; then
        oie_curl_args+=(--cacert "$OIE_CACERT")
    elif [[ "${OIE_INSECURE:-false}" == "true" ]]; then
        oie_curl_args+=(--insecure)
    fi

    if [[ -n "${OIE_CLIENT_CERT:-}" ]]; then
        oie_curl_args+=(--cert "$OIE_CLIENT_CERT")
        [[ -n "${OIE_CLIENT_KEY:-}" ]] && oie_curl_args+=(--key "$OIE_CLIENT_KEY")
    fi
}

# oie_api <METHOD> <path> [body]
#
# <path> is relative to OIE_URL and may carry a query string. A body starting
# with @ is read from that file. Prints the response body and returns non-zero
# on any non-2xx status.
#
# The status also lands in $oie_status. Callers almost always run this inside
# $(...), which is a subshell, so a plain variable assignment would never reach
# them -- the status goes through a temp file instead.
#
# XML is used throughout rather than JSON: the Administrator's export format is
# XML, so the files in config/ round-trip through the API byte for byte. The
# JSON representation of a Channel is lossy in places (polymorphic connector
# properties), which bites on re-import.
oie_status=""
OIE_STATUS_FILE="${OIE_STATUS_FILE:-$(mktemp)}"
export OIE_STATUS_FILE

# A single EXIT trap owned by this file, with a registry so sourcing scripts can
# add their own temp files instead of installing a second EXIT trap (which would
# silently replace this one).
OIE_CLEANUP_PATHS=("$OIE_STATUS_FILE")
oie_cleanup_add() { OIE_CLEANUP_PATHS+=("$1"); }
_oie_cleanup() { rm -rf -- "${OIE_CLEANUP_PATHS[@]}" 2>/dev/null || true; }
trap _oie_cleanup EXIT

oie_api() {
    local method="$1" path="$2" body="${3:-}"
    _oie_build_args

    local args=("${oie_curl_args[@]}"
        --request "$method"
        --write-out '\n%{http_code}')

    if [[ -n "$body" ]]; then
        # An @-prefixed body is a file path; curl reads it verbatim either way.
        args+=(--header "Content-Type: application/xml" --data-binary "$body")
    fi

    local response status
    if ! response="$(curl "${args[@]}" "${OIE_URL}${path}")"; then
        printf '000' > "$OIE_STATUS_FILE"
        oie_status="000"
        return 1
    fi

    status="${response##*$'\n'}"
    printf '%s' "$status" > "$OIE_STATUS_FILE"
    oie_status="$status"

    # Everything before the final newline is the body. A 204 has none.
    if [[ "$response" == *$'\n'* ]]; then
        printf '%s' "${response%$'\n'*}"
    fi

    [[ "$status" =~ ^2 ]]
}

# oie_last_status -- the status of the most recent oie_api call, readable even
# when that call ran inside a command substitution.
oie_last_status() {
    [[ -s "$OIE_STATUS_FILE" ]] && cat "$OIE_STATUS_FILE" || printf 'unknown'
}

# oie_wait_ready [timeout_seconds]
#
# /api/server/status is annotated @DontCheckAuthorized upstream, so it answers
# before any credentials exist -- which makes it the right probe for "is the
# engine finished migrating the schema and listening?".
oie_wait_ready() {
    local timeout="${1:-180}"
    local deadline=$(( SECONDS + timeout ))
    local args=(--silent --output /dev/null --max-time 10
                --header "X-Requested-With: oie-config-push")

    if [[ -n "${OIE_CACERT:-}" ]]; then
        args+=(--cacert "$OIE_CACERT")
    elif [[ "${OIE_INSECURE:-false}" == "true" ]]; then
        args+=(--insecure)
    fi
    if [[ -n "${OIE_CLIENT_CERT:-}" ]]; then
        args+=(--cert "$OIE_CLIENT_CERT")
        [[ -n "${OIE_CLIENT_KEY:-}" ]] && args+=(--key "$OIE_CLIENT_KEY")
    fi

    printf 'waiting for %s ' "$OIE_URL" >&2
    while ! curl "${args[@]}" --fail "${OIE_URL}/server/status" 2>/dev/null; do
        if (( SECONDS >= deadline )); then
            printf ' timed out after %ss\n' "$timeout" >&2
            return 1
        fi
        printf '.' >&2
        sleep 3
    done
    printf ' ready\n' >&2
}

# Direct invocation.
if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
    if [[ $# -lt 2 ]]; then
        printf 'usage: %s <METHOD> <path> [body|@file]\n' "$(basename "$0")" >&2
        exit 2
    fi
    oie_api "$@"
    rc=$?
    printf '\n' >&2
    printf 'HTTP %s\n' "$oie_status" >&2
    exit $rc
fi
