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
# Re-importing the same alias updates it in place, and an identical one is left
# alone. When a key pair changes, the deployed channels presenting it are
# redeployed, since a running listener keeps the certificate it started with.
#
# Key pairs go through the Certificate Generator extension (/api/certgen/import,
# 0.3.0 or later), not TLS Manager's own API. TLS Manager stores only the first
# certificate of each PEM it is given and rewrites every key pair on each write,
# so going through it would drop the leaf's intermediates -- which clients need
# to verify a public CA's certificate -- and strip the chain from every other key
# pair too. Certificate Generator writes the keystore through TLS Manager's
# certificate service with the whole chain.
#
# Trusted certificates are single certificates, so they use TLS Manager's API,
# which replaces the whole list on write: this reads it, merges the new entry by
# alias, and writes it back.
#
# Requires: bash, curl, python3 (for JSON and XML assembly).

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

# JSON throughout. Through OIE_ACCEPT rather than a second Accept header: curl
# sends both, and the engine answers the first, XML.
OIE_ACCEPT=application/json

# tls_get <path> -- JSON body of a TLS Manager endpoint.
tls_get() {
    _oie_build_args
    curl "${oie_curl_args[@]}" --fail \
        "${OIE_URL}${TLS_BASE}$1"
}

# tls_put <path> <json-file>
tls_put() {
    _oie_build_args
    curl "${oie_curl_args[@]}" --fail \
        --request PUT \
        --header 'Content-Type: application/json' \
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

    # XStream's <map>, as the console sends it. PEM is base64 and dashes, but the
    # alias is the caller's, so everything is escaped.
    body="$(mktemp)"; oie_cleanup_add "$body"
    answer="$(mktemp)"; oie_cleanup_add "$answer"
    chmod 600 "$body"
    ALIAS="$alias" CERT_FILE="$cert" KEY_FILE="$key" python3 - "$body" <<'PY'
import os, sys
from xml.sax.saxutils import escape
fields = {
    "alias": os.environ["ALIAS"],
    "certificatePem": open(os.environ["CERT_FILE"]).read(),
    "privateKeyPem": open(os.environ["KEY_FILE"]).read(),
    "replace": "true",
}
with open(sys.argv[1], "w") as fh:
    fh.write("<map>" + "".join(
        "<entry><string>%s</string><string>%s</string></entry>" % (escape(k), escape(v))
        for k, v in fields.items()) + "</map>")
PY
    _oie_build_args
    status="$(curl "${oie_curl_args[@]}" \
        --request POST \
        --header 'Content-Type: application/xml' \
        --data-binary "@${body}" \
        --output "$answer" --write-out '%{http_code}' \
        "${OIE_URL}/certgen/import")" || true
    if [[ "$status" == 404 ]]; then
        printf 'the engine has no /api/certgen/import: install Certificate Generator 0.3.0 or later\n' >&2
        exit 1
    fi
    result="$(mktemp)"; oie_cleanup_add "$result"
    # {"linked-hash-map":{"entry":[{"string":["alias","x"]},{"string":"ok","boolean":true},...]}}
    STATUS="$status" python3 - "$answer" "$result" <<'PY'
import json, os, sys
status = os.environ["STATUS"]
try:
    root = json.load(open(sys.argv[1]))
    entries = (root.get("linked-hash-map") or root.get("map") or {}).get("entry") or []
except ValueError:
    sys.exit("HTTP %s from /api/certgen/import: %s" % (status, open(sys.argv[1]).read()[:300]))
entries = entries if isinstance(entries, list) else [entries]
out = {}
for e in entries:
    s = e.get("string")
    if isinstance(s, list):
        out[s[0]] = s[1]
    else:
        out[s] = next((v for k, v in e.items() if k != "string"), None)
if not out.get("ok"):
    sys.exit("key pair not imported (HTTP %s): %s" % (status, out.get("error", "no answer")))
print("key pair %s %s: %s, chain of %s, valid to %s" % (
    out["alias"], out["result"], out["subject"], out["chainLength"], out["notAfter"]))
for n, dn in enumerate(str(out.get("chain", "")).splitlines()):
    print("    %d %s" % (n, dn))
if out.get("ignored"):
    print("    %s certificate(s) in the PEM are not in its chain and were left out" % out["ignored"])
open(sys.argv[2], "w").write(out["result"])
PY

    # A deployed listener keeps presenting the key pair it was deployed with, so a
    # renewal reaches clients only once the channels presenting it redeploy. TLS
    # Manager knows those channels by name; only the ones deployed now are
    # redeployed, so a stopped channel stays stopped.
    [[ "$(cat "$result")" == updated ]] || exit 0
    in_use="$(tls_get /localCertificates)"
    _oie_build_args
    deployed="$(curl "${oie_curl_args[@]}" --fail "${OIE_URL}/channels/statuses")"
    ids="$(ALIAS="$alias" IN_USE="$in_use" DEPLOYED="$deployed" python3 - <<'PY'
import json, os
def items(node, key):
    v = ((node or {}).get("list") or {}).get(key) or []
    return v if isinstance(v, list) else [v]
alias = os.environ["ALIAS"].lower()  # PKCS#12 lowercases aliases
names = set()
for c in items(json.loads(os.environ["IN_USE"]), "localCertificate"):
    if (c.get("alias") or "").lower() == alias:
        used = (c.get("channelsInUse") or {}).get("string") or []
        names.update(used if isinstance(used, list) else [used])
for s in items(json.loads(os.environ["DEPLOYED"]), "dashboardStatus"):
    if s.get("name") in names:
        print("%s %s" % (s["channelId"], s["name"]))
PY
)"
    if [[ -z "$ids" ]]; then
        printf '    no deployed channel presents it\n'
        exit 0
    fi
    while read -r id name; do
        _oie_build_args
        curl "${oie_curl_args[@]}" --fail --request POST --output /dev/null \
            "${OIE_URL}/channels/${id}/_deploy?returnErrors=true"
        printf '    redeployed channel %s to present it\n' "$name"
    done <<<"$ids"
    ;;

trust)
    alias="${2:-}"; cert="${3:-}"
    [[ -n "$alias" && -f "${cert:-}" ]] || usage 2

    current="$(tls_get /trustedCertificates)"
    tmp="$(mktemp)"; oie_cleanup_add "$tmp"
    ALIAS="$alias" CERT_FILE="$cert" CURRENT="$current" python3 - "$tmp" <<'PY'
import json,os,sys
cur = json.loads(os.environ["CURRENT"] or "{}").get("list") or {}
items = cur.get("trustedCertificate") or []
items = items if isinstance(items, list) else [items]
alias = os.environ["ALIAS"]
entry = {"alias": alias, "certificate": open(os.environ["CERT_FILE"]).read()}
items = [i for i in items if i.get("alias") != alias] + [entry]
with open(sys.argv[1], "w") as fh:
    # The shape the GET returns; a bare array is refused with a 500.
    json.dump({"list": {"trustedCertificate": items}}, fh)
PY
    tls_put /trustedCertificates "$tmp"
    printf 'imported trusted certificate as alias %s\n' "$alias"
    ;;

*)
    usage 2
    ;;
esac
