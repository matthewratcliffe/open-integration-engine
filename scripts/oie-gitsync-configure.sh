#!/usr/bin/env bash
#
# Preconfigures the Git Sync extension from GIT_SYNC_* environment variables,
# through its settings API (POST /api/gitsync/settings), idempotently.
#
#   OIE_URL=https://127.0.0.1:8443/api OIE_PASSWORD=... \
#   GIT_SYNC_REMOTE_URL=https://gitlab.example.com/ops/oie-config.git \
#   GIT_SYNC_AUTH_TYPE=https_token GIT_SYNC_SECRET=glpat-... \
#       ./scripts/oie-gitsync-configure.sh
#
# Only the variables that are set are sent; the extension leaves a setting it
# is not sent as it is, so whatever is not set here stays as the console has
# it. A set value is applied on every run, so the console's edit of it lasts
# until the next deploy.
#
#   GIT_SYNC_REMOTE_URL           the repository, https://... or ssh://... / git@...
#   GIT_SYNC_BRANCH               branch to follow (the extension's default: main)
#   GIT_SYNC_AUTH_TYPE            none | https_token | ssh_key
#   GIT_SYNC_USERNAME             HTTPS username (GitLab: anything, e.g. oauth2)
#   GIT_SYNC_SECRET               the token, or the SSH private key (PEM, multi-line).
#                                 Stored encrypted; unset keeps the stored one
#   GIT_SYNC_MODE                 read_only | read_write (default read_only)
#   GIT_SYNC_SUBDIRECTORY         path within the repository for this engine
#   GIT_SYNC_AUTHOR_NAME, GIT_SYNC_AUTHOR_EMAIL   who commits from this engine
#   GIT_SYNC_PULL_INTERVAL_SECONDS  scheduled pull; 0 turns it off
#   GIT_SYNC_SCOPE                comma-separated: channels, code-templates,
#                                 channel-groups, configuration-map, alerts,
#                                 global-scripts, server-settings, ..., tls-manager
#                                 (TLS Manager key pairs, private keys unencrypted;
#                                 empty: the extension's default set)
#   GIT_SYNC_KNOWN_HOSTS          known_hosts lines for an SSH remote (multi-line)
#
# With none of them set it does nothing. Fails when the engine rejects the
# settings; a remote that cannot be reached yet is only a warning.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=scripts/oie-api.sh
source "${ROOT}/scripts/oie-api.sh"

# GIT_SYNC_* variable -> the extension's settings key.
FIELDS=(
    REMOTE_URL:remoteUrl
    BRANCH:branch
    AUTH_TYPE:authType
    USERNAME:username
    SECRET:secret
    MODE:mode
    SUBDIRECTORY:subdirectory
    AUTHOR_NAME:authorName
    AUTHOR_EMAIL:authorEmail
    PULL_INTERVAL_SECONDS:pullIntervalSeconds
    SCOPE:scope
    KNOWN_HOSTS:knownHosts
)

# sed, not ${v//</&lt;}: bash 5.2 reads & in a substitution's replacement as
# the matched text.
xml_escape() {
    printf '%s' "$1" | sed -e 's/&/\&amp;/g' -e 's/</\&lt;/g' -e 's/>/\&gt;/g'
}
xml_unescape() {
    sed -e 's/&lt;/</g' -e 's/&gt;/>/g' -e 's/&quot;/"/g' -e "s/&apos;/'/g" -e 's/&amp;/\&/g'
}

# The extension keeps the old value for one it does not recognise, which a
# deploy would only show as a warning; refuse it instead.
case "${GIT_SYNC_AUTH_TYPE:-none}" in
    none|NONE|https_token|HTTPS_TOKEN|ssh_key|SSH_KEY) ;;
    *) printf 'GIT_SYNC_AUTH_TYPE must be none, https_token or ssh_key, not %s\n' "$GIT_SYNC_AUTH_TYPE" >&2; exit 1 ;;
esac
case "${GIT_SYNC_MODE:-read_only}" in
    read_only|READ_ONLY|read_write|READ_WRITE) ;;
    *) printf 'GIT_SYNC_MODE must be read_only or read_write, not %s\n' "$GIT_SYNC_MODE" >&2; exit 1 ;;
esac
[[ "${GIT_SYNC_PULL_INTERVAL_SECONDS:-0}" =~ ^[0-9]+$ ]] \
    || { printf 'GIT_SYNC_PULL_INTERVAL_SECONDS must be a number of seconds, not %s\n' "$GIT_SYNC_PULL_INTERVAL_SECONDS" >&2; exit 1; }

entries="" applied=()
for field in "${FIELDS[@]}"; do
    var="GIT_SYNC_${field%%:*}" key="${field#*:}"
    [[ -n "${!var+x}" && -n "${!var}" ]] || continue
    entries+="<entry><string>${key}</string><string>$(xml_escape "${!var}")</string></entry>"
    applied+=("$var")
done

if (( ${#applied[@]} == 0 )); then
    printf 'no GIT_SYNC_* variables set: leaving Git Sync as the console has it\n'
    exit 0
fi

: "${OIE_PASSWORD:?OIE_PASSWORD is not set}"
oie_wait_ready "${OIE_WAIT_TIMEOUT:-180}"

# The body carries the credential, so it goes through a private file rather
# than curl's command line.
body_file="$(mktemp)"
oie_cleanup_add "$body_file"
chmod 600 "$body_file"
printf '<map>%s</map>' "$entries" > "$body_file"

if ! response="$(oie_api POST /gitsync/settings "@${body_file}")"; then
    if [[ "$(oie_last_status)" == 404 ]]; then
        printf 'Git Sync is not installed on this engine (/api/gitsync answered 404)\n' >&2
    else
        printf 'Git Sync rejected the settings (HTTP %s): %s\n' "$(oie_last_status)" "${response:0:400}" >&2
    fi
    exit 1
fi

# The answer is the extension's map: ok, the saved settings, and "problems" --
# fields it could not use and kept as they were.
if [[ "$response" =~ \<string\>ok\</string\>[[:space:]]*\<boolean\>false ]]; then
    printf 'Git Sync could not save the settings: %s\n' "${response:0:400}" >&2
    exit 1
fi
printf 'Git Sync settings applied from %s\n' "${applied[*]}"
problems="$(printf '%s' "$response" | tr -d '\n' \
    | sed -n 's|.*<string>problems</string>[[:space:]]*<[a-z.-]*list>\(.*\)</[a-z.-]*list>.*|\1|p' \
    | sed 's|</string>|\n|g; s|<string>||g' | sed '/^[[:space:]]*$/d' | xml_unescape)"
if [[ -n "$problems" ]]; then
    printf 'warning: Git Sync kept these settings as they were:\n' >&2
    printf '%s\n' "$problems" | sed 's/^/  - /' >&2
fi

# Whether the remote and branch answer with these settings. Not fatal: the
# remote may not be reachable yet, and the console shows the same check.
if check="$(oie_api POST /gitsync/_validate)"; then
    if [[ "$check" =~ \<string\>valid\</string\>[[:space:]]*\<boolean\>true ]]; then
        printf 'Git Sync can reach %s\n' "${GIT_SYNC_REMOTE_URL:-the configured remote}"
    else
        reason="$(printf '%s' "$check" | tr -d '\n' \
            | sed -n 's|.*<string>error</string>[[:space:]]*<string>\([^<]*\)</string>.*|\1|p' | xml_unescape)"
        printf 'warning: Git Sync cannot use the remote yet: %s\n' "${reason:-see Settings > Git Sync}" >&2
    fi
else
    printf 'warning: could not check the Git Sync remote (HTTP %s)\n' "$(oie_last_status)" >&2
fi
