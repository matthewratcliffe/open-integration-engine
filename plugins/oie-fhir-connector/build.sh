#!/usr/bin/env bash
#
# Builds the FHIR connector extension into dist/fhir-<version>.zip.
#
#   ./plugins/oie-fhir-connector/build.sh
#
# No local JDK or Maven required: it compiles inside a container and takes the
# engine jars straight out of the image this repo builds, so it is always
# compiled against exactly the engine it will run on. That matters because the
# jars are not published to Maven Central -- the usual alternative is a
# third-party mirror pinned to some other version.
#
# Like the volume monitor, this bundles nothing. Jetty (the listener's socket),
# Apache HttpClient (the sender) and Jackson (JSON) are all already in the
# engine's server-lib and visible to an extension classloader, which is how the
# core HTTP connector uses Jetty without shipping it. Unlike the volume monitor,
# the compile classpath is assembled by copying whole server-lib directories
# rather than naming jars by exact version: a dozen third-party versions would
# otherwise have to be edited here on every engine bump.
#
# Environment:
#   OIE_IMAGE      image to take engine jars from  (default oie/engine:4.6.0)
#   JDK_IMAGE      compiler image                  (default eclipse-temurin:21-jdk)
#   PLUGIN_VERSION version stamped into the metadata (default 0.1.0)

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "${HERE}/../.." && pwd)"

OIE_IMAGE="${OIE_IMAGE:-oie/engine:4.6.0}"
JDK_IMAGE="${JDK_IMAGE:-eclipse-temurin:21-jdk}"
PLUGIN_VERSION="${PLUGIN_VERSION:-0.1.0}"

# Must match the engine the extension is installed on, or ExtensionLoader
# refuses it outright -- compatibility is an exact string match.
MIRTH_VERSION="${MIRTH_VERSION:-4.6.0}"

BUILD="${HERE}/build"
LIBS="${BUILD}/libs"
DIST="${HERE}/dist"

log() { printf '==> %s\n' "$*"; }

########################################################################
# 1. Engine jars, straight from the image
########################################################################
prepare_libs() {
    mkdir -p "$LIBS"

    if [[ -f "${LIBS}/.engine-jars-ok" ]]; then
        log "engine jars already extracted (rm -rf ${BUILD} to refresh)"
        return 0
    fi

    log "extracting engine jars from ${OIE_IMAGE}"
    # A throwaway container is the only way to read an image's filesystem
    # without a running one; `docker create` does not start it.
    local cid
    cid="$(docker create "$OIE_IMAGE" /bin/true)"
    trap 'docker rm -f "$cid" >/dev/null 2>&1 || true' RETURN

    # Engine and donkey. mirth-client is needed only by the Swing settings
    # panels; the server half never loads it.
    local jar
    for jar in \
        server-lib/mirth-server.jar \
        server-lib/mirth-client-core.jar \
        server-lib/donkey/donkey-server.jar \
        client-lib/donkey-model.jar \
        client-lib/mirth-client.jar
    do
        docker cp "${cid}:/opt/engine/${jar}" "${LIBS}/$(basename "$jar")" >/dev/null
    done

    # Third-party groups, copied whole so a patch bump in the engine does not
    # break this script: jetty for the listener, commons for HttpClient and
    # commons-lang3, jackson for JSON, javax for the servlet API, log4j for the
    # logging facade.
    local group
    for group in jetty commons jackson javax log4j
    do
        docker cp "${cid}:/opt/engine/server-lib/${group}" "${LIBS}/${group}" >/dev/null
    done

    touch "${LIBS}/.engine-jars-ok"
    log "extracted $(find "${LIBS}" -name '*.jar' | wc -l) jars"
}

########################################################################
# 2. Compile
########################################################################
compile() {
    log "compiling with ${JDK_IMAGE}"
    rm -rf "${BUILD}/classes"
    mkdir -p "${BUILD}/classes"

    # Paths are passed relative to the mount so this works the same on Windows.
    MSYS_NO_PATHCONV=1 docker run --rm \
        -v "${HERE}:/p" \
        -w /p \
        "$JDK_IMAGE" \
        sh -c '
            set -e
            CP=$(find build/libs -name "*.jar" | tr "\n" ":")
            find src -name "*.java" > build/sources.txt
            echo "    $(wc -l < build/sources.txt) source files"
            javac -Xlint:-options -source 17 -target 17 \
                -cp "$CP" -d build/classes @build/sources.txt
        '
    log "compiled"
}

########################################################################
# 3. Package
########################################################################
package() {
    local stage="${BUILD}/stage/fhir"
    rm -rf "${BUILD}/stage"
    mkdir -p "${stage}" "${DIST}"

    # Three jars, because a connector is loaded in three places: the engine
    # loads SERVER + SHARED, the Swing Administrator loads CLIENT + SHARED, and
    # the properties classes in SHARED are what both ends serialise across.
    # Putting the Swing panels in the server's jar would drag mirth-client onto
    # the engine's classpath for no reason.
    MSYS_NO_PATHCONV=1 docker run --rm -v "${HERE}:/p" -w /p "$JDK_IMAGE" \
        sh -c '
            set -e
            cd build/classes
            PKG=org/openintegrationengine/connectors/fhir
            mkdir -p ../stage/fhir
            jar cf ../stage/fhir/fhir-shared.jar $(find $PKG -maxdepth 1 -name "*.class")
            jar cf ../stage/fhir/fhir-server.jar $PKG/server
            jar cf ../stage/fhir/fhir-client.jar $PKG/client
        '

    # One metadata file per connector type plus the service plugin, all pointing at
    # the same path directory -- the layout the core tcp connector uses, which ships
    # source.xml, destination.xml and plugin.xml side by side.
    local xml
    for xml in source destination plugin
    do
        sed -e "s|@PLUGIN_VERSION@|${PLUGIN_VERSION}|g" \
            -e "s|@MIRTH_VERSION@|${MIRTH_VERSION}|g" \
            "${HERE}/${xml}.xml.in" > "${stage}/${xml}.xml"
    done

    # The console UI half travels inside the extension, so one install delivers
    # both (the pattern documented in the web administrator's PLUGINS.md).
    cp -a "${HERE}/webadmin" "${stage}/webadmin"

    local zip="${DIST}/fhir-${PLUGIN_VERSION}.zip"
    rm -f "$zip"
    MSYS_NO_PATHCONV=1 docker run --rm -v "${HERE}:/p" -w /p "$JDK_IMAGE" \
        sh -c "cd build/stage && jar cfM ../../dist/fhir-${PLUGIN_VERSION}.zip fhir"

    log "built ${zip#"$ROOT/"}"
    sha256sum "$zip" | sed 's/^/    /'
}

prepare_libs
compile
package

cat <<EOF

Install it by dropping the zip in extensions/ and restarting:

    cp $(printf '%s' "${DIST}/fhir-${PLUGIN_VERSION}.zip" | sed "s|${ROOT}/||") extensions/
    docker compose up -d --force-recreate engine
    ./scripts/oie-check-extensions.sh "FHIR Listener"
EOF
