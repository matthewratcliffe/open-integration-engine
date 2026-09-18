#!/usr/bin/env bash
#
# Builds the SFTP connector extension into dist/sftp-<version>.zip.
#
#   ./plugins/oie-sftp-connector/build.sh
#
# No local JDK or Maven required: it compiles inside a container and takes the
# engine jars straight out of the image this repo builds, so it is always
# compiled against exactly the engine it will run on. That matters because the
# jars are not published to Maven Central -- the usual alternative is a
# third-party mirror pinned to some other version.
#
# The extension ships three jars, which is the split the engine's extension
# loader expects of a connector:
#
#   sftp-shared.jar   the properties classes and the servlet interface; loaded by
#                     both the server and the Swing Administrator, because both
#                     deserialise the same channel XML
#   sftp-server.jar   the engine half: the embedded SFTP server, the client, the
#                     source and destination connectors
#   sftp-client.jar   the Swing Administrator's settings panels
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

# Apache MINA SSHD is the SFTP *server* (push mode). There is no alternative in
# Java worth considering, and the engine ships nothing that can act as a server.
#
# The client half deliberately uses the engine's own jsch-2.27.7 instead, which
# is already on the server classpath and is what the built-in File connector
# uses for its sftp scheme -- so a channel migrated from the File Reader behaves
# the same way, and this extension adds no client-side jar at all.
#
# MINA logs through slf4j, and nothing is bundled for it: the engine already
# ships slf4j-api and the slf4j-log4j12 binding in server-lib/donkey, so MINA's
# authentication and key-exchange messages land in mirth.log on their own.
# Bundling a second binding only produces SLF4J's "multiple bindings" warning.
SSHD_VERSION="2.19.0"

BUILD="${HERE}/build"
LIBS="${BUILD}/libs"
DIST="${HERE}/dist"

declare -a MAVEN_DEPS=(
  "org/apache/sshd/sshd-common/${SSHD_VERSION}/sshd-common-${SSHD_VERSION}.jar"
  "org/apache/sshd/sshd-core/${SSHD_VERSION}/sshd-core-${SSHD_VERSION}.jar"
  "org/apache/sshd/sshd-sftp/${SSHD_VERSION}/sshd-sftp-${SSHD_VERSION}.jar"
)

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
        server-lib/jsch-2.27.7.jar \
        server-lib/commons/commons-lang3-3.20.0.jar \
        server-lib/commons/commons-io-2.21.0.jar \
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
# 2. Third-party jars that ship inside the extension
########################################################################
fetch_maven_deps() {
    mkdir -p "${LIBS}/bundled"
    local path url name expected actual
    for path in "${MAVEN_DEPS[@]}"; do
        name="$(basename "$path")"
        if [[ -f "${LIBS}/bundled/${name}" ]]; then continue; fi
        url="https://repo1.maven.org/maven2/${path}"
        log "downloading ${name}"
        curl --fail --silent --show-error --location --retry 3 \
            -o "${LIBS}/bundled/${name}" "$url"

        # Central publishes <artifact>.sha1 beside every artifact. Verifying
        # against it catches corruption and substitution without this script
        # carrying a hardcoded digest that nobody ever re-checks. These jars are
        # loaded into the engine's JVM, so an artifact changing under us would be
        # arbitrary code execution inside the engine.
        expected="$(curl --fail --silent --show-error --location --retry 3 "${url}.sha1" | tr -d '[:space:]')"
        actual="$(sha1sum "${LIBS}/bundled/${name}" | cut -d' ' -f1)"
        if [[ -z "$expected" || "$expected" != "$actual" ]]; then
            rm -f "${LIBS}/bundled/${name}"
            printf 'checksum mismatch for %s: published %s, got %s\n' \
                "$name" "${expected:-<none>}" "$actual" >&2
            exit 1
        fi
        log "  sha1 verified"
    done

    log "bundled jars:"
    (cd "${LIBS}/bundled" && sha256sum ./*.jar | sed 's/^/    /')
}

########################################################################
# 3. Compile
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
            CP=$(ls build/libs/*.jar build/libs/bundled/*.jar 2>/dev/null | tr "\n" ":")
            find src -name "*.java" > build/sources.txt
            echo "    $(wc -l < build/sources.txt) source files"
            javac -Xlint:-options -source 17 -target 17 \
                -cp "$CP" -d build/classes @build/sources.txt
        '
    log "compiled"
}

########################################################################
# 4. Package
########################################################################
package() {
    local stage="${BUILD}/stage/sftp"
    rm -rf "${BUILD}/stage"
    mkdir -p "${stage}/lib" "${DIST}"

    # Three jars, split by which classloader needs them. The shared jar must not
    # contain anything the Swing client cannot load: it is the one both halves
    # get, and a missing server-only class there is an error dialog at login.
    MSYS_NO_PATHCONV=1 docker run --rm -v "${HERE}:/p" -w /p "$JDK_IMAGE" sh -c '
        set -e
        cd build/classes
        jar cf ../stage/sftp/sftp-shared.jar \
            $(find org/openintegrationengine/connectors/sftp -maxdepth 1 -name "*.class")
        jar cf ../stage/sftp/sftp-server.jar org/openintegrationengine/connectors/sftp/server
        jar cf ../stage/sftp/sftp-client.jar org/openintegrationengine/connectors/sftp/client
    '

    cp "${LIBS}"/bundled/*.jar "${stage}/lib/"

    # Both metadata files carry the version and the compatibility string, and
    # both have to be right or the connector is silently not loaded.
    local xml
    for xml in plugin source destination; do
        sed -e "s|@PLUGIN_VERSION@|${PLUGIN_VERSION}|g" \
            -e "s|@MIRTH_VERSION@|${MIRTH_VERSION}|g" \
            -e "s|@SSHD_VERSION@|${SSHD_VERSION}|g" \
            "${HERE}/${xml}.xml.in" > "${stage}/${xml}.xml"
    done

    # The console UI half travels inside the extension, so one install delivers
    # the panels for both administrators (the pattern documented in the web
    # administrator's PLUGINS.md).
    cp -a "${HERE}/webadmin" "${stage}/webadmin"

    local zip="${DIST}/sftp-${PLUGIN_VERSION}.zip"
    rm -f "$zip"
    MSYS_NO_PATHCONV=1 docker run --rm -v "${HERE}:/p" -w /p "$JDK_IMAGE" \
        sh -c "cd build/stage && jar cfM ../../dist/sftp-${PLUGIN_VERSION}.zip sftp"

    log "built ${zip#"$ROOT/"}"
    sha256sum "$zip" | sed 's/^/    /'
}

prepare_libs
fetch_maven_deps
compile
package

cat <<EOF

Install it by dropping the zip in extensions/ and restarting:

    cp $(printf '%s' "${DIST}/sftp-${PLUGIN_VERSION}.zip" | sed "s|${ROOT}/||") extensions/
    docker compose up -d --force-recreate engine
    ./scripts/oie-check-extensions.sh "SFTP Listener" "SFTP Sender" "SFTP Connector"
EOF
