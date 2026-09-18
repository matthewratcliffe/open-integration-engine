#!/usr/bin/env bash
#
# Builds the Null connector extension into dist/nullsender-<version>.zip.
#
#   ./plugins/oie-null-connector/build.sh
#
# No local JDK or Maven required: it compiles inside a container and takes the
# engine jars straight out of the image this repo builds, so it is always
# compiled against exactly the engine it will run on. That matters because the
# jars are not published to Maven Central -- the usual alternative is a
# third-party mirror pinned to some other version.
#
# This bundles nothing at all. The connector's only dependencies are the engine
# itself: donkey for the connector API, mirth-server for the ACK generator the
# source connectors use, log4j-api for the logger, and Swing. Nothing is
# downloaded, so there is no checksum step here -- unlike the SFTP connector,
# which ships Apache MINA.
#
# Environment:
#   OIE_IMAGE      image to take engine jars from    (default oie/engine:4.6.0)
#   JDK_IMAGE      compiler image                    (default eclipse-temurin:21-jdk)
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

    # client-lib is copied whole rather than jar by jar. It is flat, it already
    # holds donkey-model, mirth-client, mirth-client-core, xstream and log4j-api,
    # and naming its jars individually would mean editing this script for every
    # patch bump in the engine.
    docker cp "${cid}:/opt/engine/client-lib" "${LIBS}/client-lib" >/dev/null

    # The server half needs two more: mirth-server for ACKGenerator and the
    # controllers, donkey-server for DestinationConnector.
    docker cp "${cid}:/opt/engine/server-lib/mirth-server.jar" "${LIBS}/" >/dev/null
    docker cp "${cid}:/opt/engine/server-lib/donkey/donkey-server.jar" "${LIBS}/" >/dev/null

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
# 3. Prove the channel XML round-trips
########################################################################
#
# The failure this guards against is the one this repo keeps warning about: a
# properties class the engine can write but not read back is stored as an
# InvalidChannel, and every API call still reports success. Serialising the
# defaults and reading them back with the engine's own ObjectXMLSerializer --
# after the same allowTypes() call the service plugin makes at startup -- catches
# it here instead of on a server.
verify() {
    log "verifying the properties round-trip through XStream"

    mkdir -p "${BUILD}/verify"
    cat > "${BUILD}/verify/RoundTrip.java" <<'JAVA'
import com.mirth.connect.model.converters.ObjectXMLSerializer;
import org.openintegrationengine.connectors.nullsender.NullDispatcherProperties;

import java.util.Collections;
import java.util.List;

public class RoundTrip {
    public static void main(String[] args) throws Exception {
        ObjectXMLSerializer serializer = ObjectXMLSerializer.getInstance();
        serializer.init("4.6.0");
        serializer.processAnnotations(new Class[] { NullDispatcherProperties.class });
        serializer.allowTypes(Collections.<String>emptyList(),
                List.of("org.openintegrationengine.connectors.nullsender.**"),
                Collections.<String>emptyList());

        NullDispatcherProperties original = new NullDispatcherProperties();
        String xml = serializer.serialize(original);
        System.out.println(xml);

        NullDispatcherProperties read = serializer.deserialize(xml, NullDispatcherProperties.class);
        if (!original.equals(read)) {
            throw new IllegalStateException("properties did not survive the round-trip");
        }
        System.out.println("round-trip ok");
    }
}
JAVA

    MSYS_NO_PATHCONV=1 docker run --rm -v "${HERE}:/p" -w /p "$JDK_IMAGE" sh -c '
        set -e
        CP="$(find build/libs -name "*.jar" | tr "\n" ":")build/classes"
        javac -nowarn -cp "$CP" -d build/verify build/verify/RoundTrip.java
        java -cp "${CP}:build/verify" RoundTrip
    ' | sed 's/^/    /'
}

########################################################################
# 4. Package
########################################################################
package() {
    local stage="${BUILD}/stage/nullsender"
    rm -rf "${BUILD}/stage"
    mkdir -p "${stage}" "${DIST}"

    # Three jars, because a connector is loaded in three places: the engine loads
    # SERVER + SHARED, the Swing Administrator loads CLIENT + SHARED, and the
    # properties classes in SHARED are what both ends serialise across. Putting
    # the Swing panel in the server's jar would drag mirth-client onto the
    # engine's classpath for no reason; putting the dispatcher in the shared jar
    # would ask the Administrator to resolve mirth-server.
    MSYS_NO_PATHCONV=1 docker run --rm -v "${HERE}:/p" -w /p "$JDK_IMAGE" \
        sh -c '
            set -e
            cd build/classes
            PKG=org/openintegrationengine/connectors/nullsender
            mkdir -p ../stage/nullsender
            jar cf ../stage/nullsender/nullsender-shared.jar $(find $PKG -maxdepth 1 -name "*.class")
            jar cf ../stage/nullsender/nullsender-server.jar $PKG/server
            jar cf ../stage/nullsender/nullsender-client.jar $PKG/client
        '

    # Both metadata files carry the version and the compatibility string, and both
    # have to be right or the connector is silently not loaded.
    local xml
    for xml in plugin destination
    do
        sed -e "s|@PLUGIN_VERSION@|${PLUGIN_VERSION}|g" \
            -e "s|@MIRTH_VERSION@|${MIRTH_VERSION}|g" \
            "${HERE}/${xml}.xml.in" > "${stage}/${xml}.xml"
    done

    # And both have to be well-formed, which is easier to get wrong than it
    # sounds: a comment containing a double hyphen is illegal XML, and the
    # launcher's answer to metadata it cannot parse is one ERROR line at startup
    # followed by carrying on without the extension. Parsed here with the JDK's
    # own parser rather than xmllint, which the compiler image does not have.
    MSYS_NO_PATHCONV=1 docker run --rm -v "${HERE}:/p" -w /p "$JDK_IMAGE" sh -c '
        set -e
        cat > /tmp/CheckXml.java <<JAVA
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;

public class CheckXml {
    public static void main(String[] args) throws Exception {
        for (String path : args) {
            DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new File(path));
            System.out.println("    " + path + ": well-formed");
        }
    }
}
JAVA
        java /tmp/CheckXml.java build/stage/nullsender/plugin.xml build/stage/nullsender/destination.xml
    '

    # The console UI half travels inside the extension, so one install delivers
    # the panels for both administrators (the pattern documented in the web
    # administrator's PLUGINS.md).
    cp -a "${HERE}/webadmin" "${stage}/webadmin"

    local zip="${DIST}/nullsender-${PLUGIN_VERSION}.zip"
    rm -f "$zip"
    MSYS_NO_PATHCONV=1 docker run --rm -v "${HERE}:/p" -w /p "$JDK_IMAGE" \
        sh -c "cd build/stage && jar cfM ../../dist/nullsender-${PLUGIN_VERSION}.zip nullsender"

    log "built ${zip#"$ROOT/"}"
    sha256sum "$zip" | sed 's/^/    /'
}

prepare_libs
compile
verify
package

cat <<EOF

Install it by dropping the zip in extensions/ and restarting:

    cp $(printf '%s' "${DIST}/nullsender-${PLUGIN_VERSION}.zip" | sed "s|${ROOT}/||") extensions/
    docker compose up -d --force-recreate engine
    ./scripts/oie-check-extensions.sh "Null Sender" "Null Connector Service Plugin"
EOF
