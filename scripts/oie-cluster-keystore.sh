#!/usr/bin/env bash
#
# Extracts the engine's keystore so every node in a cluster can share it.
#
#   docker compose up -d                       # one engine, once
#   ./scripts/oie-cluster-keystore.sh          # -> secrets/keystore.jks
#
# Why this exists, and why it is the first step of standing up a cluster:
#
# appdata/keystore.jks holds two things -- the self-signed certificate the admin
# listener presents, and the secret key the engine's Encryptor uses for channel
# properties (connector passwords) and for message content where a channel
# encrypts it. DefaultConfigurationController.configureEncryption() looks for the
# "encryption" alias at startup and generates one if it is missing, so an engine
# with an empty appdata quietly creates a key of its own.
#
# In a cluster that is the one mistake with no way back. Node A encrypts with its
# key, node B cannot read it, and by the time anyone notices the data has been
# written. Every node has to start from the same keystore, which means producing
# one and distributing it rather than letting each engine invent its own.
#
# Options:
#   --out <path>        where to write it     (default secrets/keystore.jks)
#   --volume <name>     docker volume to read (default oie_engine-appdata)
#   --container <name>  read from a container instead of a volume
#   --force             overwrite an existing output file
#
# Once it exists, every engine gets KEYSTORE_SOURCE=/run/secrets/keystore.jks.
# In Kubernetes the same file becomes a Secret:
#
#   kubectl create secret generic oie-keystore --from-file=keystore.jks=secrets/keystore.jks

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

OUT="${ROOT}/secrets/keystore.jks"
VOLUME="${OIE_APPDATA_VOLUME:-oie_engine-appdata}"
CONTAINER=""
FORCE=false

while [[ $# -gt 0 ]]; do
    case "$1" in
        --out) OUT="$2"; shift 2 ;;
        --volume) VOLUME="$2"; shift 2 ;;
        --container) CONTAINER="$2"; shift 2 ;;
        --force) FORCE=true; shift ;;
        -h|--help) sed -n '2,32p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) printf 'unknown argument: %s\n' "$1" >&2; exit 2 ;;
    esac
done

log() { printf '==> %s\n' "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

# Replacing a keystore that engines have already encrypted data with makes that
# data unreadable, so the overwrite is never implicit. It is the operator's call,
# taken with the existing file in view.
if [[ -e "$OUT" && "$FORCE" != "true" ]]; then
    cat >&2 <<EOF
ERROR: ${OUT} already exists.

That file is very likely the key your cluster's data is encrypted with.
Replacing it makes every encrypted channel property and message content
written under the old key unreadable, permanently.

If you are certain this one is not in use:   --force
If you want to keep it:                      nothing to do, it is already there.
EOF
    exit 1
fi

command -v docker >/dev/null 2>&1 || die "docker is required"

mkdir -p "$(dirname "$OUT")"
tmp="$(mktemp)"
trap 'rm -f "$tmp"' EXIT

if [[ -n "$CONTAINER" ]]; then
    log "reading keystore from container ${CONTAINER}"
    docker cp "${CONTAINER}:/opt/engine/appdata/keystore.jks" "$tmp" \
        || die "could not read the keystore from ${CONTAINER}"
else
    docker volume inspect "$VOLUME" >/dev/null 2>&1 \
        || die "no docker volume named ${VOLUME}. Start the stack once (docker compose up -d), or pass --volume/--container."
    log "reading keystore from volume ${VOLUME}"
    # A throwaway container is the only way to read a volume without a running
    # one. Binary-safe: cat to stdout, and nothing else writes to stdout.
    docker run --rm -v "${VOLUME}:/appdata:ro" alpine:3.22 \
        sh -c 'cat /appdata/keystore.jks' > "$tmp" \
        || die "could not read keystore.jks from ${VOLUME}"
fi

[[ -s "$tmp" ]] || die "the keystore came back empty; has the engine finished its first start?"

# JCEKS begins CE CE CE CE; JKS begins FE ED FE ED. Checked because an engine
# that has not finished starting, or a wrong volume, yields a short or empty
# file that would otherwise be distributed to every node before anyone noticed.
magic="$(head -c 4 "$tmp" | od -An -tx1 | tr -d ' \n')"
case "$magic" in
    cececece) kind="JCEKS" ;;
    feedfeed) kind="JKS" ;;
    *) die "that file is not a Java keystore (starts ${magic})" ;;
esac

cp "$tmp" "$OUT"
chmod 600 "$OUT" 2>/dev/null || true

log "wrote ${OUT#"$ROOT/"} (${kind}, $(wc -c < "$OUT") bytes)"
cat <<EOF

    Keep it out of git -- secrets/ is ignored -- and back it up with the
    database. Restoring one without the other is not a restore.

    Local cluster:
        docker compose -f compose.yaml -f compose.cluster.yaml up -d

    Kubernetes:
        kubectl create secret generic oie-keystore \\
            --from-file=keystore.jks=${OUT#"$ROOT/"}
EOF
