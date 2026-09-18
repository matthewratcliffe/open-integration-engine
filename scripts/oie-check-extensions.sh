#!/usr/bin/env bash
#
# Fails if an extension the configuration depends on is not loaded on the
# server.
#
#   OIE_REQUIRED_EXTENSIONS="Zen SSL Extension,HTTPS Listener" \
#     ./scripts/oie-check-extensions.sh
#   ./scripts/oie-check-extensions.sh "Zen SSL Extension" "HTTPS Listener"
#
# With no arguments and no OIE_REQUIRED_EXTENSIONS it just lists what is
# loaded, which is the quickest way to find the exact names to assert on.
#
# Why this is needed: an extension that fails to load does not stop the engine.
# OIE matches an extension's <mirthVersion> against the server version with an
# exact string comparison, so an extension built for Mirth Connect 4.5.x is
# refused on OIE 4.6.0, logged once at startup, and then simply absent. The
# first visible symptom is every channel using its connectors being stored as
# an invalid channel. Nothing in the REST API reports the rejection, so the
# check has to be "is the thing I need present?".
#
# Names are matched case-insensitively against both plugin and connector
# metadata, so "HTTP Listener" (a connector) and "HTTP Authentication Settings"
# (a plugin) both work.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=scripts/oie-api.sh
source "${ROOT}/scripts/oie-api.sh"

required=()
if [[ $# -gt 0 ]]; then
    required=("$@")
elif [[ -n "${OIE_REQUIRED_EXTENSIONS:-}" ]]; then
    # Comma-separated, so extension names may contain spaces.
    IFS=',' read -ra required <<< "$OIE_REQUIRED_EXTENSIONS"
fi

oie_wait_ready "${OIE_WAIT_TIMEOUT:-180}"

plugins_xml="$(oie_api GET /extensions/plugins)"
connectors_xml="$(oie_api GET /extensions/connectors)"

# Both endpoints return a map whose <name> elements are the extension names.
extract_names() {
    printf '%s' "$1" | tr '<' '\n' | sed -n 's|^name>\(.*\)|\1|p'
}

loaded=()
while IFS= read -r n; do
    [[ -n "$n" ]] && loaded+=("$n")
done < <( { extract_names "$plugins_xml"; extract_names "$connectors_xml"; } | sort -u )

if (( ${#required[@]} == 0 )); then
    printf '%d extensions loaded on %s:\n' "${#loaded[@]}" "$OIE_URL"
    printf '  %s\n' "${loaded[@]}"
    printf '\nAssert on these with:\n'
    printf '  OIE_REQUIRED_EXTENSIONS="Name One,Name Two" %s\n' "$(basename "$0")"
    exit 0
fi

lower() { printf '%s' "$1" | tr '[:upper:]' '[:lower:]'; }

missing=()
for want in "${required[@]}"; do
    # Trim whitespace left by splitting on commas.
    want="${want#"${want%%[![:space:]]*}"}"
    want="${want%"${want##*[![:space:]]}"}"
    [[ -n "$want" ]] || continue

    found=false
    for have in "${loaded[@]}"; do
        if [[ "$(lower "$have")" == "$(lower "$want")" ]]; then
            found=true
            break
        fi
    done

    if [[ "$found" == "true" ]]; then
        printf 'ok       %s\n' "$want"
    else
        printf 'MISSING  %s\n' "$want" >&2
        missing+=("$want")
    fi
done

if (( ${#missing[@]} > 0 )); then
    printf '\n%d required extension(s) are not loaded on %s.\n' \
        "${#missing[@]}" "$OIE_URL" >&2
    printf 'Check the startup log for an incompatibility:\n' >&2
    printf "  docker compose logs engine | grep -i 'not compatible'\n" >&2
    printf 'An extension built for an older version needs a vendor build for\n' >&2
    printf 'this version, or OIE_EXTENSION_RETAG_VERSION=true.\n' >&2
    printf 'See extensions/README.md.\n' >&2
    exit 1
fi

printf '\nall %d required extension(s) present\n' "${#required[@]}"
