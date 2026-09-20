#!/usr/bin/env bash
set -u
PROPS="$(mktemp)"
fail=0

set_prop() {
    local key="$1" value="$2"
    [[ -w "$PROPS" ]] || return 0
    _SP_KEY="$key" _SP_VAL="$value" awk '
        BEGIN { key=ENVIRON["_SP_KEY"]; val=ENVIRON["_SP_VAL"]; re=key; gsub(/\./,"\\.",re); re="^[ \t]*" re "[ \t]*="; written=0 }
        $0 ~ re { if(!written){print key" = "val; written=1} next }
        { print }
        END { if(!written) print key" = "val }
    ' "$PROPS" > "${PROPS}.new"
    mv "${PROPS}.new" "$PROPS"
}
mp_key() { local n="$1"; local k="${n:4}"; k="${k//__/-}"; k="${k//_/.}"; k="${k,,}"; printf '%s' "$k"; }
sanitise_name_id() { printf '%s' "$1" | tr -c 'A-Za-z0-9._-' '-' | cut -c1-36; }
derive_uuid_id() { local seed="$1" hex; hex="$(printf '%s' "$seed" | sha1sum | cut -c1-32)"; printf '%s' "${hex:0:8}-${hex:8:4}-5${hex:13:3}-8${hex:17:3}-${hex:20:12}"; }
parse_ext_entry() { local entry="$1"; expected=""; url="$entry"; if [[ "$entry" == sha256:* ]]; then expected="${entry#sha256:}"; expected="${expected%%@*}"; url="${entry#sha256:${expected}@}"; fi; }
esc() { printf '%s' "$1" | sed -e 's/&/\&amp;/g' -e 's/</\&lt;/g' -e 's/>/\&gt;/g'; }
expand() { local value="$1" var; while [[ "$value" =~ \$\{([A-Za-z_][A-Za-z0-9_]*)\} ]]; do var="${BASH_REMATCH[1]}"; value="${value//\$\{$var\}/${!var:-}}"; done; printf '%s' "$value"; }

check() { if [[ "$2" == "$3" ]]; then echo "OK   $1"; else echo "FAIL $1: got [$2] want [$3]"; fail=1; fi; }

printf 'aXb = keepme\na.b = 1\n' > "$PROPS"; set_prop a.b 2
check "regex-safe-keepme" "$(grep -c '^aXb = keepme$' "$PROPS")" "1"
check "regex-safe-set"    "$(grep '^a\.b = ' "$PROPS")" "a.b = 2"

printf 'database.password = old\n' > "$PROPS"; set_prop database.password 'p/a&s\word'
check "special-chars" "$(grep '^database\.password = ' "$PROPS")" 'database.password = p/a&s\word'

printf 'a.b = 1\nkeep = y\na.b = 2\n' > "$PROPS"; set_prop a.b 9
check "dedup-count" "$(grep -c '^a\.b = ' "$PROPS")" "1"

check "mp-single" "$(mp_key _MP_SERVER_INITIALADMINPASSWORD)" "server.initialadminpassword"
check "mp-double" "$(mp_key _MP_DATABASE_MAX__CONNECTIONS)" "database.max-connections"
check "sanitise"  "$(sanitise_name_id 'pod/weird name!')" "pod-weird-name-"
r=$(sanitise_name_id 'aaaaaaaaaabbbbbbbbbbccccccccccddddddddddeeee'); check "cap36" "${#r}" "36"

a=$(derive_uuid_id 'default/engine-0'); b=$(derive_uuid_id 'default/engine-0'); c=$(derive_uuid_id 'default/engine-1')
check "uuid-deterministic" "$a" "$b"
[[ "$a" =~ ^[0-9a-f]{8}-[0-9a-f]{4}-5[0-9a-f]{3}-8[0-9a-f]{3}-[0-9a-f]{12}$ ]] && echo "OK   uuid-shape" || { echo "FAIL uuid-shape: $a"; fail=1; }
[[ "$a" != "$c" ]] && echo "OK   uuid-differs" || { echo "FAIL uuid-differs"; fail=1; }

parse_ext_entry "https://example.org/ext/websupport-1.0.3.zip"
check "ext-unpinned-exp" "$expected" ""
parse_ext_entry "sha256:11014e5f@https://github.com/o/r/releases/download/v1/websupport.zip"
check "ext-pinned-exp" "$expected" "11014e5f"
check "ext-pinned-url" "$url" "https://github.com/o/r/releases/download/v1/websupport.zip"
parse_ext_entry "sha256:deadbeef@https://user@host.example/ext.zip?token=a@b"
check "ext-at-in-url-exp" "$expected" "deadbeef"
check "ext-at-in-url-url" "$url" "https://user@host.example/ext.zip?token=a@b"

export DOWNSTREAM_FHIR_URL="https://fhir.example.org"
check "expand-set" "$(expand 'endpoint=${DOWNSTREAM_FHIR_URL}/r4')" "endpoint=https://fhir.example.org/r4"
unset MISSING_VAR
check "expand-unset" "$(expand 'x=${MISSING_VAR}y')" "x=y"
export A_HOST=h A_PORT=9
check "expand-multi" "$(expand '${A_HOST}:${A_PORT}')" "h:9"
check "esc-all" "$(esc 'a & b < c > d')" "a &amp; b &lt; c &gt; d"
check "esc-lt" "$(esc '<')" "&lt;"

rm -f "$PROPS" "${PROPS}.new" 2>/dev/null
echo "---"; [[ $fail -eq 0 ]] && echo "ALL SHELL LOGIC OK" || echo "SHELL LOGIC FAILURES"
exit $fail
