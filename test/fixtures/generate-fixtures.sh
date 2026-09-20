#!/usr/bin/env bash
#
# Generate integration channel fixtures FROM A RUNNING ENGINE.
#
# A Mirth/OIE channel export is XStream's serialisation of Java objects, so the
# element names have to match the connector's fields exactly -- a hand-written
# one is stored as an InvalidChannel while every API call still reports success
# (see the note in scripts/oie-config-push.sh). The safe way to get a valid
# fixture for a connector is therefore to let the engine produce it: POST a
# minimal channel whose connector <properties> carries only the class attribute
# plus the handful of fields the connector needs, then GET it back -- the engine
# fills in every remaining field and returns its own canonical form.
#
# This script does that for the connectors this repo builds that have no shipped
# example channel (FHIR Listener, FHIR Sender, SFTP Sender), asserts each came
# back as a real channel rather than the InvalidChannel stub, and writes the
# read-back XML to test/fixtures/channels/. It is idempotent: channel ids are
# fixed, so a re-run overwrites.
#
# It needs an engine with those extensions installed and the API reachable --
# i.e. the integration job's engine. Environment is the usual oie-api.sh set:
#   OIE_URL, OIE_PASSWORD, OIE_INSECURE (see scripts/oie-api.sh).
#
#   ./test/fixtures/generate-fixtures.sh          # write fixtures
#   ./test/fixtures/generate-fixtures.sh --check   # generate, assert, but keep
#                                                   # the tree unchanged (CI drift check)

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OUT="${ROOT}/test/fixtures/channels"
# shellcheck source=scripts/oie-api.sh
source "${ROOT}/scripts/oie-api.sh"

CHECK_ONLY=false
[[ "${1:-}" == "--check" ]] && CHECK_ONLY=true

mkdir -p "$OUT"

fail=0
step() { printf '\n==> %s\n' "$*"; }
ok()   { printf '    ok  %s\n' "$*"; }
bad()  { printf '    FAIL %s\n' "$*" >&2; fail=1; }

# make_channel <id> <name> <sourcePropsBlock> <destPropsBlock> <sourceTransport> <destTransport>
#
# Wraps the two <properties> blocks in the smallest channel the engine accepts:
# a HL7v2 transformer on each connector and default connector properties. The
# engine backfills everything omitted, which is the whole point -- we are not
# trying to write a complete channel, only a valid seed it can complete.
make_channel() {
    local id="$1" name="$2" src_props="$3" dst_props="$4" src_tn="$5" dst_tn="$6"
    cat <<XML
<channel version="4.6.0">
  <id>${id}</id>
  <nextMetaDataId>2</nextMetaDataId>
  <name>${name}</name>
  <description>Generated fixture (test/fixtures/generate-fixtures.sh). Do not edit by hand.</description>
  <revision>1</revision>
  <sourceConnector version="4.6.0">
    <metaDataId>0</metaDataId>
    <name>sourceConnector</name>
    ${src_props}
    <transformer version="4.6.0">
      <elements/>
      <inboundDataType>HL7V2</inboundDataType>
      <outboundDataType>HL7V2</outboundDataType>
      <inboundProperties class="com.mirth.connect.plugins.datatypes.hl7v2.HL7v2DataTypeProperties" version="4.6.0"/>
      <outboundProperties class="com.mirth.connect.plugins.datatypes.hl7v2.HL7v2DataTypeProperties" version="4.6.0"/>
    </transformer>
    <filter version="4.6.0"><elements/></filter>
    <transportName>${src_tn}</transportName>
    <mode>SOURCE</mode>
    <enabled>true</enabled>
    <waitForPrevious>true</waitForPrevious>
  </sourceConnector>
  <destinationConnectors>
    <connector version="4.6.0">
      <metaDataId>1</metaDataId>
      <name>Destination 1</name>
      ${dst_props}
      <transformer version="4.6.0">
        <elements/>
        <inboundDataType>HL7V2</inboundDataType>
        <outboundDataType>HL7V2</outboundDataType>
        <inboundProperties class="com.mirth.connect.plugins.datatypes.hl7v2.HL7v2DataTypeProperties" version="4.6.0"/>
        <outboundProperties class="com.mirth.connect.plugins.datatypes.hl7v2.HL7v2DataTypeProperties" version="4.6.0"/>
      </transformer>
      <responseTransformer version="4.6.0">
        <elements/>
        <inboundDataType>HL7V2</inboundDataType>
        <outboundDataType>HL7V2</outboundDataType>
        <inboundProperties class="com.mirth.connect.plugins.datatypes.hl7v2.HL7v2DataTypeProperties" version="4.6.0"/>
        <outboundProperties class="com.mirth.connect.plugins.datatypes.hl7v2.HL7v2DataTypeProperties" version="4.6.0"/>
      </transformer>
      <filter version="4.6.0"><elements/></filter>
      <transportName>${dst_tn}</transportName>
      <mode>DESTINATION</mode>
      <enabled>true</enabled>
      <waitForPrevious>true</waitForPrevious>
    </connector>
  </destinationConnectors>
  <preprocessingScript>return message;</preprocessingScript>
  <postprocessingScript>return;</postprocessingScript>
  <deployScript>return;</deployScript>
  <undeployScript>return;</undeployScript>
  <properties version="4.6.0">
    <clearGlobalChannelMap>true</clearGlobalChannelMap>
    <messageStorageMode>DEVELOPMENT</messageStorageMode>
    <encryptData>false</encryptData>
    <encryptAttachments>false</encryptAttachments>
    <encryptCustomMetaData>false</encryptCustomMetaData>
    <removeContentOnCompletion>false</removeContentOnCompletion>
    <removeOnlyFilteredOnCompletion>false</removeOnlyFilteredOnCompletion>
    <removeAttachmentsOnCompletion>false</removeAttachmentsOnCompletion>
    <initialState>STARTED</initialState>
    <storeAttachments>true</storeAttachments>
    <metaDataColumns/>
    <attachmentProperties version="4.6.0"><type>None</type><properties/></attachmentProperties>
    <resourceIds class="linked-hash-map">
      <entry><string>Default Resource</string><string>[Default Resource]</string></entry>
    </resourceIds>
  </properties>
  <exportData>
    <metadata><enabled>true</enabled><userId>1</userId></metadata>
    <dependentIds/><dependencyIds/><channelTags/>
  </exportData>
</channel>
XML
}

# generate <id> <name> <filename> <sourceProps> <destProps> <srcTransport> <dstTransport>
#
# POST the seed, read it back, prove it is not an InvalidChannel, then write the
# engine's canonical serialisation (reformatted so diffs stay line-oriented,
# matching what oie-config-pull.sh produces).
generate() {
    local id="$1" name="$2" file="$3" src="$4" dst="$5" src_tn="$6" dst_tn="$7"
    step "$name"

    local seed tmp
    seed="$(mktemp)"; tmp="$(mktemp)"
    oie_cleanup_add "$seed"; oie_cleanup_add "$tmp"
    make_channel "$id" "$name" "$src" "$dst" "$src_tn" "$dst_tn" > "$seed"

    # Idempotent: overwrite if it already exists (override=true), else create.
    if oie_api GET "/channels/${id}" >/dev/null 2>&1; then
        oie_api PUT "/channels/${id}?override=true" "@${seed}" >/dev/null \
            || { bad "${name}: PUT failed (HTTP $(oie_last_status))"; return; }
    else
        oie_api POST "/channels" "@${seed}" >/dev/null \
            || { bad "${name}: POST failed (HTTP $(oie_last_status))"; return; }
    fi

    local xml
    if ! xml="$(oie_api GET "/channels/${id}")"; then
        bad "${name}: could not read back (HTTP $(oie_last_status))"
        return
    fi
    # The InvalidChannel stub check, the same one oie-config-push.sh uses.
    if [[ "$xml" == *"This channel is invalid"* ]] || [[ "$xml" != *"<properties class="* ]]; then
        bad "${name}: engine stored it as an InvalidChannel -- the ${src_tn}/${dst_tn} defaults do not match the connector's fields"
        return
    fi
    ok "${name}: engine accepted it and returned a real channel"

    if [[ "$CHECK_ONLY" == "true" ]]; then
        # Compare against the committed fixture so CI can flag drift.
        printf '%s\n' "$xml" | xmllint --format - > "$tmp" 2>/dev/null || printf '%s\n' "$xml" > "$tmp"
        if [[ -f "${OUT}/${file}" ]] && ! diff -q "${OUT}/${file}" "$tmp" >/dev/null 2>&1; then
            bad "${name}: committed fixture ${file} differs from what the engine now produces (re-run without --check)"
        else
            ok "${name}: committed fixture matches"
        fi
    else
        printf '%s\n' "$xml" | xmllint --format - > "${OUT}/${file}" 2>/dev/null \
            || printf '%s\n' "$xml" > "${OUT}/${file}"
        ok "wrote ${file}"
    fi

    # Leave the engine clean so a later config push is not surprised by these.
    oie_api POST "/channels/${id}/_undeploy?returnErrors=false" >/dev/null 2>&1 || true
    oie_api DELETE "/channels/${id}" >/dev/null 2>&1 || true
}

########################################################################
# Connector seeds. Each <properties> carries only its class plus the fields the
# connector genuinely needs; the engine backfills the rest. The class strings
# and field names are from the plugins' own defaults() (webadmin/web/plugin.js)
# and metadata (source.xml.in / destination.xml.in).
########################################################################

FHIR_RECEIVER='<properties class="org.openintegrationengine.connectors.fhir.FhirReceiverProperties" version="4.6.0">
      <listenerConnectorProperties version="4.6.0"><host>0.0.0.0</host><port>8085</port></listenerConnectorProperties>
      <endpointMode>RESOURCES</endpointMode>
      <fhirVersion>R4</fhirVersion>
      <basePath>/fhir</basePath>
    </properties>'

FHIR_DISPATCHER='<properties class="org.openintegrationengine.connectors.fhir.FhirDispatcherProperties" version="4.6.0">
        <mode>RESOURCES</mode>
        <fhirVersion>R4</fhirVersion>
        <serverUrl>https://fhir.invalid/fhir</serverUrl>
        <interaction>CREATE</interaction>
        <resourceType>Patient</resourceType>
        <content>${message.encodedData}</content>
      </properties>'

SFTP_DISPATCHER='<properties class="org.openintegrationengine.connectors.sftp.SftpDispatcherProperties" version="4.6.0">
        <connectionProperties>
          <host>sftp.invalid</host><port>22</port><username>oie</username>
          <authMethod>PASSWORD</authMethod><password>${sftpPassword}</password>
          <hostKeyPolicy>TRUST_ANY</hostKeyPolicy>
        </connectionProperties>
        <remoteDirectory>outgoing</remoteDirectory>
        <outputPattern>${originalFilename}</outputPattern>
      </properties>'

# A trivial, always-present counterpart connector for the other end.
CHANNEL_READER='<properties class="com.mirth.connect.connectors.vm.VmReceiverProperties" version="4.6.0">
      <sourceConnectorProperties version="4.6.0"><responseVariable>None</responseVariable></sourceConnectorProperties>
    </properties>'
JS_WRITER='<properties class="com.mirth.connect.connectors.js.JavaScriptDispatcherProperties" version="4.6.0">
        <destinationConnectorProperties version="4.6.0"/>
        <script>return;</script>
      </properties>'

step "Connecting to ${OIE_URL}"
oie_wait_ready "${OIE_WAIT_TIMEOUT:-180}"

# FHIR Listener: the connector under test is the SOURCE; a JS Writer completes it.
generate "f1000000-0000-4000-8000-000000000001" "fhir-listener-fixture" \
    "fhir-listener-fixture.xml" "$FHIR_RECEIVER" "$JS_WRITER" "FHIR Listener" "JavaScript Writer"

# FHIR Sender: the connector under test is the DESTINATION; a Channel Reader feeds it.
generate "f1000000-0000-4000-8000-000000000002" "fhir-sender-fixture" \
    "fhir-sender-fixture.xml" "$CHANNEL_READER" "$FHIR_DISPATCHER" "Channel Reader" "FHIR Sender"

# SFTP Sender: destination under test; a Channel Reader feeds it.
generate "f1000000-0000-4000-8000-000000000003" "sftp-sender-fixture" \
    "sftp-sender-fixture.xml" "$CHANNEL_READER" "$SFTP_DISPATCHER" "Channel Reader" "SFTP Sender"

printf '\n'
if (( fail )); then
    printf 'fixture generation FAILED\n' >&2
    exit 1
fi
printf 'all fixtures generated and verified as real channels\n'
