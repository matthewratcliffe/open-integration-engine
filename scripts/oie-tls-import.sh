#!/usr/bin/env bash
#
# Imports certificates into the TLS Manager plugin's stores over the REST API,
# so mTLS material is provisioned the same declarative way as channels.
#
#   # a key pair the engine presents (server cert, or a client cert for senders)
#   ./scripts/oie-tls-import.sh keypair <alias> <cert.pem> <key.pem>
#
#   # a CA or peer certificate to trust
#   ./scripts/oie-tls-import.sh trust <alias> <ca.pem>
#
#   ./scripts/oie-tls-import.sh list
#
# Channels reference this material **by alias**, so the alias is the contract
# between what CI imports here and what a channel's TLS settings name. Keep
# aliases stable across environments and the same channel XML works everywhere.
#
# The API replaces the whole list on write, so this reads the current contents,
# merges the new entry by alias, and writes back. Re-importing the same alias
# updates it in place.
#
# Requires: bash, curl, python3 (for JSON assembly).

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=scripts/oie-api.sh
source "${ROOT}/scripts/oie-api.sh"

# TLS Manager mounts at /api/tlsmanager, not under /api/extensions.
TLS_BASE="/tlsmanager"

command -v python3 >/dev/null 2>&1 || {
    printf 'python3 is required (used to assemble JSON safely).\n' >&2
    exit 1
}

usage() {
    sed -n '2,20p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
    exit "${1:-0}"
}

# tls_get <path> -- JSON body of a TLS Manager endpoint.
tls_get() {
    _oie_build_args
    curl "${oie_curl_args[@]}" --fail \
        --header 'Accept: application/json' \
        "${OIE_URL}${TLS_BASE}$1"
}

# tls_put <path> <json-file>
tls_put() {
    _oie_build_args
    curl "${oie_curl_args[@]}" --fail \
        --request PUT \
        --header 'Content-Type: application/json' \
        --header 'Accept: application/json' \
        --data-binary "@$2" \
        --output /dev/null \
        "${OIE_URL}${TLS_BASE}$1"
}

action="${1:-}"
[[ -n "$action" ]] || usage 2

oie_wait_ready "${OIE_WAIT_TIMEOUT:-180}"

case "$action" in
list)
    printf '==> local key pairs (keystore)\n'
    tls_get /localCertificates | python3 -c '
import json,sys
d=json.load(sys.stdin).get("list") or {}
items=d.get("localCertificate") or d.get("trustedCertificate") or []
items=items if isinstance(items,list) else [items]
print("    (none)" if not items else "\n".join("    %s" % i.get("alias") for i in items))'
    printf '==> trusted certificates (truststore)\n'
    tls_get /trustedCertificates | python3 -c '
import json,sys
d=json.load(sys.stdin).get("list") or {}
items=d.get("trustedCertificate") or []
items=items if isinstance(items,list) else [items]
print("    (none)" if not items else "\n".join("    %s" % i.get("alias") for i in items))'
    ;;

keypair)
    alias="${2:-}"; cert="${3:-}"; key="${4:-}"
    [[ -n "$alias" && -f "${cert:-}" && -f "${key:-}" ]] || usage 2

    current="$(tls_get /localCertificates)"
    tmp="$(mktemp)"; oie_cleanup_add "$tmp"
    ALIAS="$alias" CERT_FILE="$cert" KEY_FILE="$key" python3 - "$tmp" <<'PY' <<<"$current"
import json, os, sys

cur = json.loads(sys.stdin.read() or "{}").get("list") or {}
items = cur.get("localCertificate") or []
items = items if isinstance(items, list) else [items]

alias = os.environ["ALIAS"]
entry = {
    "alias": alias,
    "certificate": open(os.environ["CERT_FILE"]).read(),
    "key": open(os.environ["KEY_FILE"]).read(),
}
# Merge by alias, so re-importing an alias replaces it rather than duplicating.
items = [i for i in items if i.get("alias") != alias] + [entry]

with open(sys.argv[1], "w") as fh:
    json.dump(items, fh)
PY
    tls_put /localCertificates "$tmp"
    printf 'imported key pair as alias %s\n' "$alias"
    ;;

trust)
    alias="${2:-}"; cert="${3:-}"
    [[ -n "$alias" && -f "${cert:-}" ]] || usage 2

    current="$(tls_get /trustedCertificates)"
    tmp="$(mktemp)"; oie_cleanup_add "$tmp"
    ALIAS="$alias" CERT_FILE="$cert" python3 - "$tmp" <<'PY' <<<"$current"
import json,os,sys
cur = json.loads(sys.stdin.read() or "{}").get("list") or {}
items = cur.get("trustedCertificate") or []
items = items if isinstance(items, list) else [items]
alias = os.environ["ALIAS"]
entry = {"alias": alias, "certificate": open(os.environ["CERT_FILE"]).read()}
items = [i for i in items if i.get("alias") != alias] + [entry]
with open(sys.argv[1], "w") as fh:
    json.dump(items, fh)
PY
    tls_put /trustedCertificates "$tmp"
    printf 'imported trusted certificate as alias %s\n' "$alias"
    ;;

*)
    usage 2
    ;;
esac
