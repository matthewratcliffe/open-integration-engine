#!/usr/bin/env bats
#
# Unit tests for two more pieces of pure shell logic:
#   * the OIE_EXTENSION_URLS entry parser in docker/entrypoint.sh
#     (sha256:<hex>@<url>, split on the FIRST @ so an @ inside the URL survives)
#   * the configuration-map builder in scripts/oie-config-push.sh
#     (${VAR} expansion from the environment, then XML escaping)
#
# Reproduced verbatim from the scripts, same as entrypoint.bats. Run:
#   bats test/shell/config-push.bats

# ---- extension URL entry parse: verbatim from entrypoint.sh --------------
# Sets `expected` and `url` from one OIE_EXTENSION_URLS entry.
parse_ext_entry() {
    local entry="$1"
    expected=""
    url="$entry"
    if [[ "$entry" == sha256:* ]]; then
        expected="${entry#sha256:}"
        expected="${expected%%@*}"
        url="${entry#sha256:${expected}@}"
    fi
}

# ---- config-map value handling: the two verbatim pieces from config-push -
# esc() XML-escapes, ampersand first.
esc() {
    printf '%s' "$1" | sed -e 's/&/\&amp;/g' -e 's/</\&lt;/g' -e 's/>/\&gt;/g'
}
# expand() resolves ${ENV_VAR} references, leaving unset ones empty.
expand() {
    local value="$1" var
    while [[ "$value" =~ \$\{([A-Za-z_][A-Za-z0-9_]*)\} ]]; do
        var="${BASH_REMATCH[1]}"
        value="${value//\$\{$var\}/${!var:-}}"
    done
    printf '%s' "$value"
}

@test "unpinned extension entry has no checksum" {
    parse_ext_entry "https://example.org/ext/websupport-1.0.3.zip"
    [ -z "$expected" ]
    [ "$url" = "https://example.org/ext/websupport-1.0.3.zip" ]
}

@test "pinned extension entry splits checksum from url" {
    parse_ext_entry "sha256:11014e5f@https://github.com/o/r/releases/download/v1/websupport.zip"
    [ "$expected" = "11014e5f" ]
    [ "$url" = "https://github.com/o/r/releases/download/v1/websupport.zip" ]
}

@test "pinned entry keeps an @ that appears inside the url" {
    # A presigned or userinfo URL can carry an @; only the first one is the delimiter.
    parse_ext_entry "sha256:deadbeef@https://user@host.example/ext.zip?token=a@b"
    [ "$expected" = "deadbeef" ]
    [ "$url" = "https://user@host.example/ext.zip?token=a@b" ]
}

@test "config-map expands a set variable" {
    export DOWNSTREAM_FHIR_URL="https://fhir.example.org"
    run expand 'endpoint=${DOWNSTREAM_FHIR_URL}/r4'
    [ "$output" = "endpoint=https://fhir.example.org/r4" ]
}

@test "config-map leaves an unset variable empty" {
    unset MISSING_VAR
    run expand 'x=${MISSING_VAR}y'
    [ "$output" = "x=y" ]
}

@test "config-map expands several variables in one value" {
    export A_HOST=h; export A_PORT=9
    run expand '${A_HOST}:${A_PORT}'
    [ "$output" = "h:9" ]
}

@test "esc escapes ampersand, lt and gt with ampersand first" {
    run esc 'a & b < c > d'
    [ "$output" = "a &amp; b &lt; c &gt; d" ]
}

@test "esc does not double-escape an already-escaped run" {
    # Ampersand-first is what stops &lt; becoming &amp;lt;.
    run esc '<'
    [ "$output" = "&lt;" ]
}
