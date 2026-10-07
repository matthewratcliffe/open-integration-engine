#!/usr/bin/env bash
#
# Copies the shared wildcard certificate from GitLab CI/CD variables into SSM,
# where deploy/ec2/install.sh picks it up. Run by deploy/ec2/deploy.sh before
# each install, so a renewed certificate goes out with the next deploy.
#
#   TLS_CERT_PEM, TLS_KEY_PEM   the *.htrak.com cert (with its chain) and key --
#                               the instance-level variables the k8s ingress uses
#   EC2_SSM_PATH                the instance's OIE_SSM_PATH, e.g. /oie/production
#
# The deploy role needs ssm:PutParameter on ${EC2_SSM_PATH}/TLS_*.

set -euo pipefail

: "${TLS_CERT_PEM:?TLS_CERT_PEM is not set}"
: "${TLS_KEY_PEM:?TLS_KEY_PEM is not set}"
: "${EC2_SSM_PATH:?EC2_SSM_PATH is not set -- the OIE_SSM_PATH of the instance}"
EC2_SSM_PATH="${EC2_SSM_PATH%/}"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
printf '%s\n' "$TLS_CERT_PEM" > "$work/cert.pem"
printf '%s\n' "$TLS_KEY_PEM" > "$work/key.pem"

# Catch a mismatched or expired pair here rather than on the instance.
[[ "$(openssl x509 -in "$work/cert.pem" -noout -pubkey)" == "$(openssl pkey -in "$work/key.pem" -pubout)" ]] \
    || { echo "TLS_KEY_PEM is not the key for TLS_CERT_PEM" >&2; exit 1; }
openssl x509 -in "$work/cert.pem" -noout -checkend 0 >/dev/null \
    || { echo "TLS_CERT_PEM has expired" >&2; exit 1; }
openssl x509 -in "$work/cert.pem" -noout -subject -enddate

# Through --cli-input-json, so the PEM is never on a command line (where a
# leading ----- would also be read as an option) and never in the job log.
# Intelligent-Tiering moves a parameter over 4 KB -- a cert with its chain
# often is -- to the Advanced tier on its own.
put() {
    jq -n --arg name "${EC2_SSM_PATH}/$1" --rawfile value "$2" \
        '{Name: $name, Value: $value, Type: "SecureString", Overwrite: true, Tier: "Intelligent-Tiering"}' \
        > "$work/param.json"
    aws ssm put-parameter --cli-input-json "file://$work/param.json" >/dev/null
    echo "wrote ${EC2_SSM_PATH}/$1"
}
put TLS_CERT_PEM "$work/cert.pem"
put TLS_KEY_PEM "$work/key.pem"
