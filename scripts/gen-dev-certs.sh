#!/usr/bin/env bash
#
# Generates a throwaway CA, server certificate and client certificate into
# proxy/certs, so the mTLS overlay can be exercised locally.
#
#   ./scripts/gen-dev-certs.sh [hostname]
#
# These are development credentials. In a real deployment the server
# certificate comes from your normal CA or ACME, and clients-ca.crt is the
# trust anchor your partners' client certificates chain to -- generate nothing
# here, just drop those files in.
#
# On Windows run this under Git Bash with MSYS_NO_PATHCONV=1, otherwise MSYS
# rewrites the /CN=... subject into a filesystem path.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CERTS="${ROOT}/proxy/certs"
HOSTNAME_ARG="${1:-localhost}"

command -v openssl >/dev/null 2>&1 || {
    printf 'openssl not found.\n' >&2
    exit 1
}

mkdir -p "$CERTS"
cd "$CERTS"

if [[ -f clients-ca.crt && -f server.crt && -f client.crt ]]; then
    printf 'certificates already exist in proxy/certs, refusing to overwrite.\n' >&2
    printf 'Delete them first if you really want new ones.\n' >&2
    exit 1
fi

# Every openssl path below is relative to proxy/certs. That matters on Windows,
# where the native openssl build cannot open an MSYS path such as /tmp/xxxx or a
# /dev/fd process substitution -- so no mktemp and no <(...) here.
EXT_FILE=openssl-ext.cnf
cleanup() { rm -f server.csr client.csr clients-ca.srl "$EXT_FILE"; }
trap cleanup EXIT

umask 077

printf '==> dev client CA\n'
openssl req -x509 -newkey rsa:2048 -sha256 -days 365 -nodes \
    -keyout clients-ca.key -out clients-ca.crt \
    -subj "/CN=OIE Dev Client CA"

printf '==> server certificate for %s\n' "$HOSTNAME_ARG"
openssl req -newkey rsa:2048 -sha256 -nodes \
    -keyout server.key -out server.csr \
    -subj "/CN=${HOSTNAME_ARG}"
# A subjectAltName is mandatory: every current client ignores the legacy CN
# when verifying hostnames.
{
    printf 'subjectAltName=DNS:%s,DNS:localhost,IP:127.0.0.1\n' "$HOSTNAME_ARG"
    printf 'extendedKeyUsage=serverAuth\n'
} > "$EXT_FILE"
openssl x509 -req -in server.csr -sha256 -days 365 \
    -CA clients-ca.crt -CAkey clients-ca.key -CAcreateserial \
    -extfile "$EXT_FILE" -out server.crt

printf '==> client certificate\n'
openssl req -newkey rsa:2048 -sha256 -nodes \
    -keyout client.key -out client.csr \
    -subj "/CN=ci-deployer/O=Example Health"
printf 'extendedKeyUsage=clientAuth\n' > "$EXT_FILE"
openssl x509 -req -in client.csr -sha256 -days 365 \
    -CA clients-ca.crt -CAkey clients-ca.key -CAcreateserial \
    -extfile "$EXT_FILE" -out client.crt

cleanup
chmod 0644 ./*.crt
chmod 0600 ./*.key

printf '\nwrote to proxy/certs:\n'
printf '  clients-ca.crt  trust anchor nginx verifies client certificates against\n'
printf '  server.crt/key  what nginx presents to callers\n'
printf '  client.crt/key  a client identity to test with\n'
printf '\nstart the proxy and try it:\n'
printf '  docker compose -f compose.yaml -f compose.proxy.yaml up -d\n'
printf '  curl --cacert proxy/certs/clients-ca.crt \\\n'
printf '       --cert proxy/certs/client.crt --key proxy/certs/client.key \\\n'
printf '       -H "X-Requested-With: t" https://localhost/api/server/version\n'
