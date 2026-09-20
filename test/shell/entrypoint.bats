#!/usr/bin/env bats
#
# Unit tests for the pure logic in docker/entrypoint.sh.
#
# entrypoint.sh execs the engine and runs setup at the top level on source, so
# it cannot be sourced in a test. The functions under test are reproduced here
# verbatim from docker/entrypoint.sh -- the same copied-not-imported arrangement
# the plugins' JS tests use -- and each `@test` drives one. Keep this file in
# step with the script; the cases document exactly what each function promises.
#
# Run:  bats test/shell/entrypoint.bats

setup() {
    PROPS="$(mktemp)"
    export PROPS
}

teardown() {
    rm -f "$PROPS" "${PROPS}.new" 2>/dev/null || true
}

# ---- set_prop: verbatim from entrypoint.sh -------------------------------
set_prop() {
    local key="$1" value="$2"
    if [[ ! -w "$PROPS" ]]; then
        return 0
    fi
    _SP_KEY="$key" _SP_VAL="$value" awk '
        BEGIN {
            key = ENVIRON["_SP_KEY"]; val = ENVIRON["_SP_VAL"]
            re = key; gsub(/\./, "\\.", re)
            re = "^[ \t]*" re "[ \t]*="
            written = 0
        }
        $0 ~ re { if (!written) { print key " = " val; written = 1 } next }
        { print }
        END { if (!written) print key " = " val }
    ' "$PROPS" > "${PROPS}.new"
    mv "${PROPS}.new" "$PROPS"
}

# ---- the _MP_ key mangling: verbatim from entrypoint.sh ------------------
mp_key() {
    local name="$1" key
    key="${name:4}"
    key="${key//__/-}"
    key="${key//_/.}"
    key="${key,,}"
    printf '%s' "$key"
}

# ---- server id sanitisation (name style): verbatim from entrypoint.sh ----
sanitise_name_id() {
    printf '%s' "$1" | tr -c 'A-Za-z0-9._-' '-' | cut -c1-36
}

# ---- deterministic UUID server id: verbatim from entrypoint.sh -----------
derive_uuid_id() {
    local seed="$1" hex
    hex="$(printf '%s' "$seed" | sha1sum | cut -c1-32)"
    printf '%s' "${hex:0:8}-${hex:8:4}-5${hex:13:3}-8${hex:17:3}-${hex:20:12}"
}

@test "set_prop appends a key that is absent" {
    printf 'existing = 1\n' > "$PROPS"
    set_prop database.username mirthdb
    grep -q '^database.username = mirthdb$' "$PROPS"
    grep -q '^existing = 1$' "$PROPS"
}

@test "set_prop replaces the first assignment in place" {
    printf 'http.port = 0\nother = x\n' > "$PROPS"
    set_prop http.port 8080
    grep -q '^http.port = 8080$' "$PROPS"
    grep -q '^other = x$' "$PROPS"
}

@test "set_prop drops later duplicate assignments" {
    printf 'a.b = 1\nkeep = y\na.b = 2\n' > "$PROPS"
    set_prop a.b 9
    run grep -c '^a.b = ' "$PROPS"
    [ "$output" = "1" ]
    grep -q '^a.b = 9$' "$PROPS"
    grep -q '^keep = y$' "$PROPS"
}

@test "set_prop does not treat the dotted key as a regex" {
    # a.b must not also match aXb.
    printf 'aXb = keepme\na.b = 1\n' > "$PROPS"
    set_prop a.b 2
    grep -q '^aXb = keepme$' "$PROPS"
    grep -q '^a.b = 2$' "$PROPS"
}

@test "set_prop preserves values containing slash, ampersand and backslash" {
    printf 'database.password = old\n' > "$PROPS"
    local pw='p/a&s\\word'
    set_prop database.password "$pw"
    run grep '^database.password = ' "$PROPS"
    [ "$output" = "database.password = $pw" ]
}

@test "_MP_ mangling turns single underscore into dot" {
    [ "$(mp_key _MP_SERVER_INITIALADMINPASSWORD)" = "server.initialadminpassword" ]
}

@test "_MP_ mangling turns double underscore into hyphen" {
    [ "$(mp_key _MP_DATABASE_MAX__CONNECTIONS)" = "database.max-connections" ]
}

@test "server id name style keeps allowed chars and dashes the rest" {
    [ "$(sanitise_name_id 'oie-worker-1')" = "oie-worker-1" ]
    [ "$(sanitise_name_id 'pod/weird name!')" = "pod-weird-name-" ]
}

@test "server id name style caps at 36 characters" {
    result="$(sanitise_name_id 'aaaaaaaaaabbbbbbbbbbccccccccccddddddddddeeee')"
    [ "${#result}" -eq 36 ]
}

@test "derived UUID server id is deterministic and well-shaped" {
    a="$(derive_uuid_id 'default/engine-0')"
    b="$(derive_uuid_id 'default/engine-0')"
    [ "$a" = "$b" ]
    # 8-4-4-4-12, version nibble 5, variant nibble 8.
    [[ "$a" =~ ^[0-9a-f]{8}-[0-9a-f]{4}-5[0-9a-f]{3}-8[0-9a-f]{3}-[0-9a-f]{12}$ ]]
}

@test "derived UUID server id differs when the seed differs" {
    a="$(derive_uuid_id 'default/engine-0')"
    b="$(derive_uuid_id 'default/engine-1')"
    [ "$a" != "$b" ]
}
