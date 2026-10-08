#!/usr/bin/env bash
#
# Creates the default "Connector errors (all channels)" alert, once.
#
#   OIE_URL=https://127.0.0.1:8443/api OIE_PASSWORD=... ./scripts/oie-default-alert.sh
#
# The alert fires on every source and destination connector error -- a remote
# that refuses the connection, an unknown host, a timeout -- on every channel,
# including channels created after it (scripts/default-alert/connector-errors.xml).
# It sends no email. The engine only counts an alert as alerted, on the Alerts
# page, when it has an action (DefaultAlertWorker.triggerAction), so its one
# action sends each error to the "Alert inbox" channel
# (scripts/default-alert/alert-inbox.xml): a Channel Reader that keeps what it
# receives, one message per error, and opens no port.
#
# Create-only: when the alert exists -- by its id, whatever it has been renamed
# or changed to in the console -- nothing is touched. Delete it in the console
# and the next run creates it again; set OIE_DEFAULT_ALERT=false to stop that.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=scripts/oie-api.sh
source "${ROOT}/scripts/oie-api.sh"

DIR="${ROOT}/scripts/default-alert"
ALERT_ID=6f1c3a52-0f5e-4c1e-9a8b-0c0de0a1e7a1
INBOX_ID=a1e7b0c5-3d4e-4f60-9a1b-2c3d4e5f6a70

if [[ "${OIE_DEFAULT_ALERT:-true}" != true ]]; then
    printf 'OIE_DEFAULT_ALERT=%s: not creating the default alert\n' "${OIE_DEFAULT_ALERT}"
    exit 0
fi
[[ -r "${DIR}/connector-errors.xml" && -r "${DIR}/alert-inbox.xml" ]] \
    || { printf 'missing %s/connector-errors.xml or alert-inbox.xml\n' "$DIR" >&2; exit 1; }

: "${OIE_PASSWORD:?OIE_PASSWORD is not set}"
oie_wait_ready "${OIE_WAIT_TIMEOUT:-180}"

# exists <path> -- true when the engine returns the object; it answers a
# missing one with an empty body.
exists() {
    local body
    body="$(oie_api GET "$1")" || return 1
    [[ -n "${body//[[:space:]]/}" ]]
}

if exists "/alerts/${ALERT_ID}"; then
    printf 'default alert already exists, leaving it as it is\n'
    exit 0
fi

if exists "/channels/${INBOX_ID}"; then
    printf 'Alert inbox channel exists\n'
else
    oie_api POST /channels "@${DIR}/alert-inbox.xml" >/dev/null \
        || { printf 'could not create the Alert inbox channel (HTTP %s)\n' "$(oie_last_status)" >&2; exit 1; }
    printf 'created the Alert inbox channel\n'
fi
# The alert's action reaches only a deployed channel. A deploy can take well
# over the default minute on a busy engine.
OIE_TIMEOUT="${OIE_DEPLOY_TIMEOUT:-300}" oie_api POST "/channels/${INBOX_ID}/_deploy" >/dev/null \
    || { printf 'could not deploy the Alert inbox channel (HTTP %s)\n' "$(oie_last_status)" >&2; exit 1; }

oie_api POST /alerts "@${DIR}/connector-errors.xml" >/dev/null \
    || { printf 'could not create the default alert (HTTP %s)\n' "$(oie_last_status)" >&2; exit 1; }
printf 'created the "Connector errors (all channels)" alert: errors appear on the Alerts page and in the Alert inbox channel\n'
