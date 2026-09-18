#!/usr/bin/env bash
#
# Builds the Random Generator connector extension into dist/generator-<version>.zip.
#
#   ./plugins/oie-random-generator/build.sh
#
# No local JDK required: it compiles inside a container and takes the engine jars
# straight out of the image this repo builds, so it is always compiled against
# exactly the engine it will run on. That matters because the jars are not
# published to Maven Central -- the usual alternative is a third-party mirror
# pinned to some other version.
#
# Nothing is downloaded. This connector needs no third-party library: it
# manufactures text from a template using the JDK and the engine's own classes,
# so the extension is three jars and a handful of sample files.
#
#   generator-shared.jar   the properties classes, the message types, the sample
#                          templates and the servlet interface; loaded by both the
#                          server and the Swing Administrator, because both
#                          deserialise the same channel XML
#   generator-server.jar   the engine half: the population, the renderer and the
#                          source connector
#   generator-client.jar   the Swing Administrator's settings panel
#
# Environment:
#   OIE_IMAGE      image to take engine jars from   (default oie/engine:4.6.0)
#   JDK_IMAGE      compiler image                   (default eclipse-temurin:21-jdk)
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

    local jar
    for jar in \
        server-lib/mirth-server.jar \
        server-lib/mirth-client-core.jar \
        server-lib/xstream-1.4.20.jar \
        server-lib/commons/commons-lang3-3.20.0.jar \
        server-lib/javax/javax.servlet-api-3.1.0.jar \
        client-lib/mirth-client.jar \
        client-lib/donkey-model.jar \
        client-lib/log4j-api-2.25.3.jar \
        client-lib/javax.ws.rs-api-2.0.1.jar \
        client-lib/swagger-annotations-2.0.10.jar \
        client-lib/jersey-media-multipart-2.22.1.jar \
        client-lib/miglayout-swing-4.2.jar \
        client-lib/miglayout-core-4.2.jar \
        client-lib/swingx-core-1.6.2.jar \
        client-lib/rsyntaxtextarea-2.5.6.jar
    do
        docker cp "${cid}:/opt/engine/${jar}" "${LIBS}/$(basename "$jar")" >/dev/null
    done
    docker cp "${cid}:/opt/engine/server-lib/donkey/donkey-server.jar" "${LIBS}/" >/dev/null

    touch "${LIBS}/.engine-jars-ok"
    log "extracted $(ls -1 "${LIBS}"/*.jar | wc -l) engine jars"
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
            CP=$(ls build/libs/*.jar 2>/dev/null | tr "\n" ":")
            find src -name "*.java" > build/sources.txt
            echo "    $(wc -l < build/sources.txt) source files"
            javac -Xlint:-options -source 17 -target 17 \
                -cp "$CP" -d build/classes @build/sources.txt
        '

    # The samples are resources of the shared jar: GeneratorSamples reads them
    # from the classpath, which is what lets the engine, the Swing panel and the
    # preview all use one copy of each.
    mkdir -p "${BUILD}/classes/org/openintegrationengine/connectors/generator/samples"
    cp "${HERE}"/samples/*.hl7 \
        "${BUILD}/classes/org/openintegrationengine/connectors/generator/samples/"

    log "compiled"
}

########################################################################
# 3. The web console's copy of the samples
########################################################################
#
# An engine-served console plugin is fetched as text and imported from a blob:
# URL, so it cannot import a sibling module or fetch a file next to itself --
# whatever it needs has to be in the one file. Rather than keep a second copy of
# every sample in JavaScript, where it would drift from the .hl7 files the engine
# actually sends, build.sh inlines them at the @SAMPLES@ marker.
#
# The panel degrades cleanly if this ever fails: an empty map means the template
# box starts blank, and a blank template makes the engine fall back to the sample
# for the selected type anyway.
inline_samples() {
    local plugin="${BUILD}/stage/generator/webadmin/web/plugin.js"
    local generated="${BUILD}/samples.js.part"

    {
        printf 'const SAMPLES = {\n'
        local file name
        for file in "${HERE}"/samples/*.hl7; do
            name="$(basename "$file" .hl7)"
            printf '    %s: "' "$name"
            # Escape for a JavaScript double-quoted string, one segment per \n.
            awk '{ gsub(/\\/, "\\\\"); gsub(/"/, "\\\""); printf "%s\\n", $0 }' "$file"
            printf '",\n'
        done
        printf '};\n'
    } > "$generated"

    awk -v generated="$generated" '
        /@SAMPLES@/ {
            while ((getline line < generated) > 0) print line
            close(generated)
            next
        }
        { print }
    ' "$plugin" > "${plugin}.new"
    mv "${plugin}.new" "$plugin"

    log "inlined $(ls -1 "${HERE}"/samples/*.hl7 | wc -l) sample templates into the console plugin"
}

########################################################################
# 4. Package
########################################################################
package() {
    local stage="${BUILD}/stage/generator"
    rm -rf "${BUILD}/stage"
    mkdir -p "${stage}" "${DIST}"

    # Three jars, split by which classloader needs them. The shared jar must not
    # contain anything the Swing client cannot load: it is the one both halves
    # get, and a missing server-only class there is an error dialog at login.
    MSYS_NO_PATHCONV=1 docker run --rm -v "${HERE}:/p" -w /p "$JDK_IMAGE" sh -c '
        set -e
        cd build/classes
        jar cf ../stage/generator/generator-shared.jar \
            $(find org/openintegrationengine/connectors/generator -maxdepth 1 -name "*.class") \
            org/openintegrationengine/connectors/generator/samples
        jar cf ../stage/generator/generator-server.jar org/openintegrationengine/connectors/generator/server
        jar cf ../stage/generator/generator-client.jar org/openintegrationengine/connectors/generator/client
    '

    # Both metadata files carry the version and the compatibility string, and
    # both have to be right or the connector is silently not loaded.
    local xml
    for xml in plugin source; do
        sed -e "s|@PLUGIN_VERSION@|${PLUGIN_VERSION}|g" \
            -e "s|@MIRTH_VERSION@|${MIRTH_VERSION}|g" \
            "${HERE}/${xml}.xml.in" > "${stage}/${xml}.xml"
    done

    # The console UI half travels inside the extension, so one install delivers
    # the panels for both administrators (the pattern documented in the web
    # administrator's PLUGINS.md).
    cp -a "${HERE}/webadmin" "${stage}/webadmin"
    inline_samples

    local zip="${DIST}/generator-${PLUGIN_VERSION}.zip"
    rm -f "$zip"
    MSYS_NO_PATHCONV=1 docker run --rm -v "${HERE}:/p" -w /p "$JDK_IMAGE" \
        sh -c "cd build/stage && jar cfM ../../dist/generator-${PLUGIN_VERSION}.zip generator"

    log "built ${zip#"$ROOT/"}"
    sha256sum "$zip" | sed 's/^/    /'
}

prepare_libs
compile
package

cat <<EOF

Install it by dropping the zip in extensions/ and restarting:

    cp $(printf '%s' "${DIST}/generator-${PLUGIN_VERSION}.zip" | sed "s|${ROOT}/||") extensions/
    docker compose up -d --force-recreate engine
    ./scripts/oie-check-extensions.sh "Random Generator" "Random Generator Connector"
EOF
