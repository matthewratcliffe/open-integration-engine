#!/usr/bin/env bash
#
# Copies the EC2 installer's settings from GitLab CI/CD variables into SSM
# Parameter Store under EC2_SSM_PATH, where install.sh reads them. Run by
# deploy.sh before each install, so GitLab is where they are set -- scoped to
# the job's environment (production/oie1), like the ECS deploy's.
#
# Only the names below are copied, each as a SecureString named after the
# variable. One that is not set in GitLab is left as it is in SSM, so a value
# put there by hand still works.
#
#   RDS_ENDPOINT          defaults to the shared RDS instance in shared-outputs.json
#   RDS_MASTER_USERNAME,  default to the shared outputs' encrypted master
#   RDS_MASTER_PASSWORD   credentials, decrypted with ENCRYPTION_KEY, as the
#                         ECS plan does; the installer uses them to create the
#                         engine's user and database
#   DATABASE_USERNAME     defaults to EC2_INSTANCE_NAME (deploy.sh sets it)
#   KEYSTORE_BASE64       defaults to OIE_KEYSTORE_B64, the ECS name for it
#   everything else in SETTINGS below, as the installer names it
#   TLS_CERT_PEM, TLS_KEY_PEM   the *.htrak.com wildcard, checked first
#   OIDC_*                the ECS deploy's SSO variables, as OIE_OIDC_SETTINGS
#
# Deliberately not copied: RDS_DATABASE_NAME. In production/oie1 that names
# the ECS engine's database, and two engines must not share one. The EC2
# engine's database is POSTGRES_DB (default mirthdb).
#
# The deploy role needs ssm:PutParameter on <EC2_SSM_PATH>/*.

set -euo pipefail

: "${EC2_SSM_PATH:?EC2_SSM_PATH is not set -- the OIE_SSM_PATH of the instance}"
EC2_SSM_PATH="${EC2_SSM_PATH%/}"

SETTINGS=(
    RDS_ENDPOINT DATABASE_URL DATABASE_USERNAME DATABASE_PASSWORD POSTGRES_DB
    RDS_MASTER_USERNAME RDS_MASTER_PASSWORD
    OIE_ADMIN_PASSWORD KEYSTORE_PASSWORD KEYSTORE_BASE64 KEYSTORE_RESET
    OIE_HEAP_MAX HTTPS_PORT HTTP_REDIRECT WEB_LOCAL_LOGIN API_DOCS_REQUIRE_SSO OIE_UPDATE_CHECK TZ
    ADMIN_PASSWORD_FORCE OIE_DEFAULT_ALERT
    GIT_SYNC_REMOTE_URL GIT_SYNC_BRANCH GIT_SYNC_AUTH_TYPE GIT_SYNC_USERNAME GIT_SYNC_SECRET
    GIT_SYNC_MODE GIT_SYNC_SUBDIRECTORY GIT_SYNC_AUTHOR_NAME GIT_SYNC_AUTHOR_EMAIL
    GIT_SYNC_PULL_INTERVAL_SECONDS GIT_SYNC_SCOPE GIT_SYNC_KNOWN_HOSTS
)

########################################################################
# Defaults from the shared outputs, as the ECS plan job derives them
########################################################################
outputs="${CI_PROJECT_DIR:-.}/shared-outputs.json"
if [[ -s "$outputs" ]]; then
    if [[ -z "${RDS_ENDPOINT:-}" && -z "${DATABASE_URL:-}" ]]; then
        RDS_ENDPOINT="$(jq -r '.postgresql_db_instance_address | (.value? // .) // empty' "$outputs")"
    fi
    decrypt() {
        jq -r "$1 // empty" "$outputs" \
            | openssl enc -d -aes-256-cbc -pbkdf2 -salt -a -A -pass "pass:${ENCRYPTION_KEY}"
    }
    if [[ -n "${ENCRYPTION_KEY:-}" ]]; then
        [[ -n "${RDS_MASTER_USERNAME:-}" ]] || RDS_MASTER_USERNAME="$(decrypt .postgresql_db_master_username_enc)"
        [[ -n "${RDS_MASTER_PASSWORD:-}" ]] || RDS_MASTER_PASSWORD="$(decrypt .postgresql_db_master_password_enc)"
    fi
fi
KEYSTORE_BASE64="${KEYSTORE_BASE64:-${OIE_KEYSTORE_B64:-}}"

########################################################################
# Write
########################################################################
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

# put <name> <file> -- through --cli-input-json, so a value is never on a
# command line (where a leading - would also be read as an option) and never in
# the job log. Intelligent-Tiering moves one over 4 KB -- a keystore or a cert
# with its chain often is -- to the Advanced tier on its own.
put() {
    jq -n --arg name "${EC2_SSM_PATH}/$1" --rawfile value "$2" \
        '{Name: $name, Value: ($value | rtrimstr("\n")), Type: "SecureString", Overwrite: true, Tier: "Intelligent-Tiering"}' \
        > "$work/param.json"
    aws ssm put-parameter --cli-input-json "file://$work/param.json" >/dev/null \
        || { echo "could not write ${EC2_SSM_PATH}/$1 -- the deploy role needs ssm:PutParameter on ${EC2_SSM_PATH}/*" >&2; exit 1; }
}

written=() skipped=()
for name in "${SETTINGS[@]}"; do
    if [[ -n "${!name:-}" ]]; then
        printf '%s\n' "${!name}" > "$work/value"
        put "$name" "$work/value"
        written+=("$name")
    else
        skipped+=("$name")
    fi
done
echo "wrote to ${EC2_SSM_PATH}: ${written[*]:-nothing}"
echo "not set in GitLab, left as they are in SSM: ${skipped[*]:-none}"

########################################################################
# Single sign-on (docs/sso.md)
########################################################################
# The same OIDC_* variables and checks as the ECS deploy (.gitlab-ci.yml),
# written as one parameter, OIE_OIDC_SETTINGS: a JSON object of the OIE_OIDC_*
# overrides, which the installer expands. One parameter rewritten on every
# deploy, so a variable removed in GitLab is gone from the instance too: the
# extension reads a present-but-empty override as set, and SSM cannot hold an
# empty value to clear one. OIDC_ENABLED unset writes {}, which pins nothing
# and leaves SSO to the console. OIDC_AUTO_REDIRECT=true hides the password
# form but does not disable password login, which stays the break-glass path.
if [[ -z "${OIDC_ENABLED:-}" ]]; then
    for v in OIDC_DISCOVERY_URL OIDC_CLIENT_ID OIDC_CLIENT_SECRET OIDC_PROVIDER_LABEL OIDC_USERNAME_CLAIM OIDC_SCOPES OIDC_AUTO_REDIRECT OIDC_JIT_ENABLED; do
        [[ -z "${!v:-}" ]] \
            || { echo "$v is set but OIDC_ENABLED is not - set OIDC_ENABLED=true (or false) to pin SSO from GitLab, or unset $v" >&2; exit 1; }
    done
    echo '{}' > "$work/oidc.json"
    echo "OIDC_ENABLED unset - SSO is configured in the console"
else
    case "$OIDC_ENABLED" in true|false) ;; *) echo "OIDC_ENABLED must be true or false, not '$OIDC_ENABLED'" >&2; exit 1 ;; esac
    case "${OIDC_AUTO_REDIRECT:-}" in
        ""|false) ;;
        true) [[ "$OIDC_ENABLED" == true ]] || { echo "OIDC_AUTO_REDIRECT=true needs OIDC_ENABLED=true" >&2; exit 1; } ;;
        *) echo "OIDC_AUTO_REDIRECT must be true or false, not '$OIDC_AUTO_REDIRECT'" >&2; exit 1 ;;
    esac
    # JIT provisioning ("JIT provision unknown users") is on whenever SSO is
    # pinned on, so anyone the provider admits gets an engine account.
    # OIDC_JIT_ENABLED=false turns it off.
    jit=""
    if [[ "$OIDC_ENABLED" == true ]]; then
        for v in OIDC_DISCOVERY_URL OIDC_CLIENT_ID OIDC_CLIENT_SECRET; do
            [[ -n "${!v:-}" ]] || { echo "OIDC_ENABLED=true needs $v" >&2; exit 1; }
        done
        jit="${OIDC_JIT_ENABLED:-true}"
        case "$jit" in true|false) ;; *) echo "OIDC_JIT_ENABLED must be true or false, not '$jit'" >&2; exit 1 ;; esac
    elif [[ -n "${OIDC_JIT_ENABLED:-}" ]]; then
        echo "OIDC_JIT_ENABLED needs OIDC_ENABLED=true" >&2; exit 1
    fi
    : "${EC2_HOSTNAME:?EC2_HOSTNAME is not set -- the host name of the instance, for the web administrator URL}"
    webadmin="https://${EC2_HOSTNAME}"
    [[ -z "${HTTPS_PORT:-}" || "$HTTPS_PORT" == 443 ]] || webadmin+=":${HTTPS_PORT}"
    webadmin+=/oie-webadmin
    jq -n \
        --arg enabled "$OIDC_ENABLED" \
        --arg webadmin "$webadmin" \
        --arg discovery "${OIDC_DISCOVERY_URL:-}" \
        --arg client "${OIDC_CLIENT_ID:-}" \
        --arg secret "${OIDC_CLIENT_SECRET:-}" \
        --arg label "${OIDC_PROVIDER_LABEL:-}" \
        --arg claim "${OIDC_USERNAME_CLAIM:-}" \
        --arg scopes "${OIDC_SCOPES:-}" \
        --arg redirect "${OIDC_AUTO_REDIRECT:-}" \
        --arg jit "$jit" \
        '{OIE_OIDC_ENABLED: $enabled, OIE_OIDC_WEB_ADMINISTRATOR_URL: $webadmin, OIE_OIDC_DISCOVERY_URL: $discovery, OIE_OIDC_CLIENT_ID: $client, OIE_OIDC_CLIENT_SECRET: $secret, OIE_OIDC_PROVIDER_LABEL: $label, OIE_OIDC_USERNAME_CLAIM: $claim, OIE_OIDC_SCOPES: $scopes, OIE_OIDC_AUTO_REDIRECT: $redirect, OIE_OIDC_JIT_ENABLED: $jit} | with_entries(select(.value != ""))' \
        > "$work/oidc.json"
    echo "SSO pinned from GitLab: $(jq -c 'del(.OIE_OIDC_CLIENT_SECRET)' "$work/oidc.json")${OIDC_CLIENT_SECRET:+ + OIE_OIDC_CLIENT_SECRET}"
    echo "Register ${webadmin}/oidc/callback as the redirect URI at the provider"
fi
put OIE_OIDC_SETTINGS "$work/oidc.json"

########################################################################
# The certificate
########################################################################
if [[ -z "${TLS_CERT_PEM:-}" && -z "${TLS_KEY_PEM:-}" ]]; then
    echo "TLS_CERT_PEM/TLS_KEY_PEM not set: leaving the certificate in SSM as it is"
    exit 0
fi
: "${TLS_CERT_PEM:?TLS_KEY_PEM is set but TLS_CERT_PEM is not}"
: "${TLS_KEY_PEM:?TLS_CERT_PEM is set but TLS_KEY_PEM is not}"
printf '%s\n' "$TLS_CERT_PEM" > "$work/cert.pem"
printf '%s\n' "$TLS_KEY_PEM" > "$work/key.pem"
# Catch a mismatched or expired pair here rather than on the instance.
[[ "$(openssl x509 -in "$work/cert.pem" -noout -pubkey)" == "$(openssl pkey -in "$work/key.pem" -pubout)" ]] \
    || { echo "TLS_KEY_PEM is not the key for TLS_CERT_PEM" >&2; exit 1; }
openssl x509 -in "$work/cert.pem" -noout -checkend 0 >/dev/null \
    || { echo "TLS_CERT_PEM has expired" >&2; exit 1; }
openssl x509 -in "$work/cert.pem" -noout -subject -enddate
put TLS_CERT_PEM "$work/cert.pem"
put TLS_KEY_PEM "$work/key.pem"
echo "wrote ${EC2_SSM_PATH}/TLS_CERT_PEM and TLS_KEY_PEM"
