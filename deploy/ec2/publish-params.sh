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
    OIE_HEAP_MAX HTTPS_PORT HTTP_REDIRECT OIE_UPDATE_CHECK TZ
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
