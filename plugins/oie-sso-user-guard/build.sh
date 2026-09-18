#!/usr/bin/env bash
#
# Builds the SSO User Guard extension into dist/ssouserguard-<version>.zip.
#
#   ./plugins/oie-sso-user-guard/build.sh
#
# There is nothing to compile. This extension is a console UI and no server
# code at all -- no serverClasses, no jar, no apiProvider -- so the build is a
# version stamp and a zip. It still needs the JDK image for `jar`, so that the
# archive is written the same way as every other extension here and the host
# needs no zip utility.
#
# Environment:
#   JDK_IMAGE      image providing `jar`               (default eclipse-temurin:21-jdk)
#   PLUGIN_VERSION version stamped into plugin.xml     (default 0.1.0)

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "${HERE}/../.." && pwd)"

JDK_IMAGE="${JDK_IMAGE:-eclipse-temurin:21-jdk}"
PLUGIN_VERSION="${PLUGIN_VERSION:-0.1.0}"

# Must match the engine the extension is installed on, or ExtensionLoader
# refuses it outright -- compatibility is an exact string match.
MIRTH_VERSION="${MIRTH_VERSION:-4.6.0}"

BUILD="${HERE}/build"
DIST="${HERE}/dist"

log() { printf '==> %s\n' "$*"; }

package() {
    local stage="${BUILD}/stage/ssouserguard"
    rm -rf "${BUILD}/stage"
    mkdir -p "$stage" "$DIST"

    sed -e "s|@PLUGIN_VERSION@|${PLUGIN_VERSION}|g" \
        -e "s|@MIRTH_VERSION@|${MIRTH_VERSION}|g" \
        "${HERE}/plugin.xml.in" > "${stage}/plugin.xml"

    # The console half is the whole extension.
    cp -a "${HERE}/webadmin" "${stage}/webadmin"

    # Keep the version in the console manifest in step with the one in
    # plugin.xml: the console shows it on the Extensions page, and two versions
    # for one extension is the kind of small lie that costs an hour later.
    sed -i.bak "s|\"version\": \"[^\"]*\"|\"version\": \"${PLUGIN_VERSION}\"|" \
        "${stage}/webadmin/plugin.json"
    rm -f "${stage}/webadmin/plugin.json.bak"

    local zip="${DIST}/ssouserguard-${PLUGIN_VERSION}.zip"
    rm -f "$zip"
    MSYS_NO_PATHCONV=1 docker run --rm -v "${HERE}:/p" -w /p "$JDK_IMAGE" \
        sh -c "cd build/stage && jar cfM ../../dist/ssouserguard-${PLUGIN_VERSION}.zip ssouserguard"

    log "built ${zip#"$ROOT/"}"
    sha256sum "$zip" | sed 's/^/    /'
}

package

cat <<EOF

Install it by dropping the zip in extensions/ and restarting:

    cp $(printf '%s' "${DIST}/ssouserguard-${PLUGIN_VERSION}.zip" | sed "s|${ROOT}/||") extensions/
    docker compose up -d --force-recreate engine
    ./scripts/oie-check-extensions.sh "SSO User Guard"

It only does anything on an engine that also has the OIDC Authentication
extension: with no SSO-bound accounts, every Edit User dialog stays editable.
EOF
