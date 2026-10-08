#!/usr/bin/env bash
#
# Builds the EC2 installer bundle: install.sh plus everything it installs, so
# the instance needs no access to GitHub -- only to GitLab (to fetch the
# bundle, see deploy.sh) and SSM.
#
#   ./deploy/ec2/package.sh [output.tar.gz]     default dist/oie-ec2.tar.gz
#
#   oie-ec2/
#     install.sh
#     oie-gate.py          the proxy gate install.sh runs beside nginx
#     release.env          version, plugin list and defaults, from this commit
#     entrypoint.sh        docker/entrypoint.sh, unchanged
#     scripts/             scripts/*.sh (the admin bootstrap and its helpers)
#     extensions/          what compose mounts from ./extensions
#     payload/             the release tarball, plugin and extension zips, and
#                          SHA256SUMS
#
# The release and plugins are the ones docker/Dockerfile builds, downloaded and
# checked against the same pinned checksums. The community extensions are the
# ones the ECS task installs at boot (extension_urls in infra/aws/variables.tf)
# -- Web Support among them, without which /oie-webadmin/ is a 404 -- or, when
# set, OIE_EXTENSION_URLS, which replaces the whole list as it does for ECS.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OUT="${1:-${ROOT}/dist/oie-ec2.tar.gz}"

log() { printf '%s [package] %s\n' "$(date -u '+%Y-%m-%d %H:%M:%S')" "$*"; }
die() { log "ERROR: $*"; exit 1; }

dockerfile_arg() {
    sed -n "s/^ARG $1=\"\{0,1\}\([^\"]*\)\"\{0,1\}\$/\1/p" "${ROOT}/docker/Dockerfile" | sed -n 1p
}
dockerfile_plugins() {
    # A multi-line ARG: join the continuation lines, then take the quoted value.
    sed -n '/^ARG OIE_BUILTIN_PLUGIN_URLS=/,/"$/p' "${ROOT}/docker/Dockerfile" \
        | tr -d '\n' | sed 's/\\//g; s/^ARG OIE_BUILTIN_PLUGIN_URLS="//; s/"$//'
}

OIE_VERSION="${OIE_VERSION:-$(dockerfile_arg OIE_VERSION)}"
OIE_SHA256="${OIE_SHA256:-$(dockerfile_arg OIE_SHA256)}"
OIE_BUILTIN_PLUGIN_URLS="${OIE_BUILTIN_PLUGIN_URLS-$(dockerfile_plugins)}"
[[ -n "$OIE_VERSION" && -n "$OIE_SHA256" ]] || die "could not read OIE_VERSION/OIE_SHA256 from docker/Dockerfile"
tf_extension_urls() {
    sed -n '/^variable "extension_urls"/,/^}/p' "${ROOT}/infra/aws/variables.tf" \
        | grep -o '"sha256:[^"]*"' | tr -d '"' | paste -sd, -
}
OIE_EXTENSION_URLS="${OIE_EXTENSION_URLS:-$(tf_extension_urls)}"
[[ -n "$OIE_EXTENSION_URLS" ]] || die "could not read extension_urls from infra/aws/variables.tf"
# The update check's watch list defaults to the one compose.yaml passes.
update_check_extensions="$(sed -n 's/.*OIE_UPDATE_CHECK_EXTENSIONS:-\([^}]*\)}.*/\1/p' "${ROOT}/compose.yaml" | sed -n 1p)"
commit="${CI_COMMIT_SHA:-$(git -C "$ROOT" rev-parse HEAD 2>/dev/null || echo unknown)}"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
bundle="$work/oie-ec2"
mkdir -p "$bundle/payload/plugins" "$bundle/payload/extensions" "$bundle/scripts" "$bundle/extensions"

# fetch <url> <dest> <sha256>
fetch() {
    log "fetching $1"
    curl --fail --silent --show-error --location --retry 3 -o "$2" "$1"
    if [[ -n "$3" ]]; then
        echo "${3}  ${2}" | sha256sum -c - >/dev/null || die "checksum mismatch for $1"
    else
        log "WARNING: no checksum pinned for $1"
    fi
}

url="${OIE_TARBALL_URL:-https://github.com/OpenIntegrationEngine/engine/releases/download/v${OIE_VERSION}/oie_unix_${OIE_VERSION//./_}.tar.gz}"
fetch "$url" "$bundle/payload/oie.tar.gz" "$OIE_SHA256"

# fetch_list <comma-separated [sha256:<hex>@]<url> list> <dir> -- numbered, so
# the installer unpacks them in the list's order. Sets idx to the count.
fetch_list() {
    local entry expected entries
    idx=0
    IFS=',' read -ra entries <<<"$1"
    for entry in "${entries[@]}"; do
        [[ -n "$entry" ]] || continue
        idx=$((idx + 1))
        expected=""
        if [[ "$entry" == sha256:* ]]; then
            expected="${entry#sha256:}"; expected="${expected%%@*}"
            entry="${entry#sha256:${expected}@}"
        fi
        fetch "$entry" "$2/$(printf '%02d' "$idx")-${entry##*/}" "$expected"
    done
}
fetch_list "$OIE_EXTENSION_URLS" "$bundle/payload/extensions"
extensions=$idx
fetch_list "$OIE_BUILTIN_PLUGIN_URLS" "$bundle/payload/plugins"

# Checked again on the instance, so a bundle damaged on the way is refused.
(cd "$bundle/payload" && find . -type f ! -name SHA256SUMS | sort | xargs sha256sum > SHA256SUMS)

cp "${ROOT}/deploy/ec2/install.sh" "$bundle/install.sh"
cp "${ROOT}/deploy/ec2/oie-gate.py" "$bundle/oie-gate.py"
cp "${ROOT}/docker/entrypoint.sh" "$bundle/entrypoint.sh"
cp "${ROOT}"/scripts/*.sh "$bundle/scripts/"
find "${ROOT}/extensions" -mindepth 1 -maxdepth 1 ! -name README.md -exec cp -R {} "$bundle/extensions/" \;
chmod 0755 "$bundle/install.sh" "$bundle/entrypoint.sh" "$bundle"/scripts/*.sh

{
    printf 'OIE_VERSION=%q\n' "$OIE_VERSION"
    printf 'OIE_SHA256=%q\n' "$OIE_SHA256"
    printf 'OIE_UPDATE_CHECK_EXTENSIONS_DEFAULT=%q\n' "$update_check_extensions"
    printf 'OIE_BUNDLE_COMMIT=%q\n' "$commit"
} > "$bundle/release.env"

mkdir -p "$(dirname "$OUT")"
tar -czf "$OUT" -C "$work" oie-ec2
log "wrote ${OUT} ($(du -h "$OUT" | cut -f1)): OIE ${OIE_VERSION}, ${idx} plugins, ${extensions} extensions, commit ${commit}"
