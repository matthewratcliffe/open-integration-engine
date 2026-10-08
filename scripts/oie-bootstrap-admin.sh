#!/usr/bin/env bash
#
# Rotates the admin password away from the shipped default, idempotently.
#
#   OIE_ADMIN_PASSWORD=... ./scripts/oie-bootstrap-admin.sh
#
# Why this exists: OIE 4.6.0 seeds the admin account with the classic
# admin/admin. The `server.initialadminpassword` property that lets you seed it
# from config landed on main *after* the 4.6.0 release, so setting it on this
# version does nothing -- the stack has to rotate the password itself on first
# boot. Drop this script once a release that honours the property ships (the
# compose file already passes _MP_SERVER_INITIALADMINPASSWORD, so that path
# starts working on its own and this script then no-ops).
#
# Runs in three states:
#   already rotated  -> OIE_ADMIN_PASSWORD works, exit 0, password unchanged
#   fresh install    -> admin/admin works, set the password, exit 0
#   neither works    -> exit 1 without touching anything
#
# The first two also settle the web administrator's first-login welcome dialog
# for admin (settle_first_login).

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=scripts/oie-api.sh
source "${ROOT}/scripts/oie-api.sh"

: "${OIE_ADMIN_PASSWORD:?OIE_ADMIN_PASSWORD is not set}"

DEFAULT_PASSWORD="${OIE_DEFAULT_ADMIN_PASSWORD:-admin}"

oie_wait_ready "${OIE_WAIT_TIMEOUT:-180}"

# can_login <password> -- true when these credentials are accepted.
can_login() {
    OIE_PASSWORD="$1" oie_api GET /users/current >/dev/null 2>&1
}

# user_id_of <password> -- the numeric id the /users/{id}/... endpoints want,
# from /users/current, rather than assuming the admin account is always id 1.
user_id_of() {
    local user_xml
    user_xml="$(OIE_PASSWORD="$1" oie_api GET /users/current)" || return 1
    # Matched in-shell: `sed | head -1` would take SIGPIPE under `set -o pipefail`.
    [[ "$user_xml" =~ \<id\>([0-9]+)\</id\> ]] || return 1
    printf '%s' "${BASH_REMATCH[1]}"
}

# The web administrator opens a "Welcome to Open Integration Engine" dialog
# -- new password, then a registration form -- while the account's firstlogin
# preference is unset or true. The password is the one configured here, so
# settle the flag. (SSO accounts the OIDC extension creates have it settled
# by the extension.) Cosmetic, so a failure only warns.
settle_first_login() {
    local user_id status
    if user_id="$(user_id_of "$OIE_ADMIN_PASSWORD")"; then
        OIE_PASSWORD="$OIE_ADMIN_PASSWORD" _oie_build_args
        status="$(curl "${oie_curl_args[@]}" --output /dev/null --write-out '%{http_code}' \
            --request PUT --header 'Content-Type: text/plain' --data-binary false \
            "${OIE_URL}/users/${user_id}/preferences/firstlogin")" || status=000
        if [[ "$status" =~ ^2 ]]; then
            printf 'first-login welcome dialog settled for %s\n' "${OIE_USER}"
            return 0
        fi
    fi
    printf 'warning: could not clear the first-login flag for %s (HTTP %s); the web administrator will show its welcome dialog once\n' \
        "${OIE_USER}" "${status:-000}" >&2
}

if can_login "$OIE_ADMIN_PASSWORD"; then
    printf 'admin password is already set as configured\n'
    settle_first_login
    exit 0
fi

if ! can_login "$DEFAULT_PASSWORD"; then
    printf 'cannot authenticate as %s with either the configured or the default password.\n' \
        "${OIE_USER}" >&2
    printf 'If the password was changed by hand, set OIE_ADMIN_PASSWORD to match it.\n' >&2
    exit 1
fi

printf 'admin is still on the shipped default password, rotating it\n'

user_id="$(user_id_of "$DEFAULT_PASSWORD")" || {
    printf 'could not read the admin user id from /users/current\n' >&2
    exit 1
}

# The endpoint takes the plaintext password as a text/plain body and answers
# with a list of unmet password requirements -- an empty list means accepted.
_oie_build_args_with() {
    OIE_PASSWORD="$DEFAULT_PASSWORD" _oie_build_args
}
_oie_build_args_with

response="$(curl "${oie_curl_args[@]}" \
    --request PUT \
    --header 'Content-Type: text/plain' \
    --header 'Accept: application/xml' \
    --data-binary "$OIE_ADMIN_PASSWORD" \
    --write-out '\n%{http_code}' \
    "${OIE_URL}/users/${user_id}/password")"

status="${response##*$'\n'}"
body="${response%$'\n'*}"

if [[ ! "$status" =~ ^2 ]]; then
    printf 'password change failed (HTTP %s): %s\n' "$status" "${body:0:300}" >&2
    exit 1
fi

# A non-empty <list> means the new password failed the configured complexity
# rules; the old one is still in force, so say so loudly.
if printf '%s' "$body" | grep -q '<string>'; then
    printf 'the new password was rejected by the password policy:\n' >&2
    printf '%s\n' "$body" | sed -n 's|.*<string>\(.*\)</string>.*|  - \1|p' >&2
    exit 1
fi

if can_login "$OIE_ADMIN_PASSWORD"; then
    printf 'admin password rotated successfully\n'
    settle_first_login
else
    printf 'password change reported success but the new password does not work\n' >&2
    exit 1
fi
