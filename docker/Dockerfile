# syntax=docker/dockerfile:1.7
#
# Open Integration Engine (https://github.com/OpenIntegrationEngine/engine)
#
# Built from the official release tarball rather than the upstream Docker Hub
# image, because openintegrationengine/engine on Docker Hub is still on 4.5.2
# while the current release is 4.6.0. Bump OIE_VERSION/OIE_SHA256 together --
# the checksum is taken from the `sha256sums` asset on the GitHub release.

ARG JRE_IMAGE=eclipse-temurin:21.0.9_10-jre-noble

########################################################################
# Stage 1: fetch and unpack the release
########################################################################
FROM alpine:3.22 AS fetch

ARG OIE_VERSION=4.6.0
ARG OIE_SHA256=7c82e79027e671277e1d78d0f7bbb1c53ddf1be476f1e80c2ddbfcf7855900ea
# Override to install from somewhere other than the GitHub release (e.g. an
# internal artifact mirror). Leave empty to use the canonical release URL.
ARG OIE_TARBALL_URL=

# This stack's own first-party plugins (formerly built from source under
# plugins/ in this repo, now each its own GitHub repo). Baked into the image at
# BUILD time so they behave like the bundled engine, surviving the entrypoint's
# per-boot restore of extensions/ from extensions.dist/ (see docker/entrypoint.sh).
#
# Contrast this with OIE_EXTENSION_URLS in docker/entrypoint.sh, which fetches
# extensions at container STARTUP: that mechanism exists for licensed/optional
# third-party extensions (Zen SSL, Sentinel, the web administrator, ...) that an
# operator opts into per-environment and may need to rotate without a rebuild.
# These 12 plugins are required parts of this stack, not optional add-ons, so
# they are fetched once at build time and shipped as part of the immutable
# pristine copy, exactly like the engine release itself above.
#
# Format matches OIE_EXTENSION_URLS: comma-separated sha256:<hex>@<url> entries.
#
# TODO: every checksum below is a placeholder (repeated zeros) and every
# version is a placeholder (0.1.0) because none of these 12 repos has cut a
# real release yet. Replace both once each repo's first release is tagged --
# take the checksum from that release's own sha256sums/SHA256SUMS asset, the
# same way OIE_SHA256 above is sourced for the engine itself.
ARG OIE_BUILTIN_PLUGIN_URLS="sha256:0000000000000000000000000000000000000000000000000000000000000000@https://github.com/gibson9583/oie-null-connector/releases/download/v0.1.0/nullsender-0.1.0.zip,\
sha256:0000000000000000000000000000000000000000000000000000000000000000@https://github.com/gibson9583/oie-volume-monitor/releases/download/v0.1.0/volumemonitor-0.1.0.zip,\
sha256:0000000000000000000000000000000000000000000000000000000000000000@https://github.com/gibson9583/oie-update-check/releases/download/v0.1.0/updatecheck-0.1.0.zip,\
sha256:0000000000000000000000000000000000000000000000000000000000000000@https://github.com/gibson9583/oie-sso-user-guard/releases/download/v0.1.0/ssouserguard-0.1.0.zip,\
sha256:0000000000000000000000000000000000000000000000000000000000000000@https://github.com/gibson9583/oie-sftp-connector/releases/download/v0.1.0/sftp-0.1.0.zip,\
sha256:0000000000000000000000000000000000000000000000000000000000000000@https://github.com/gibson9583/oie-random-generator/releases/download/v0.1.0/generator-0.1.0.zip,\
sha256:0000000000000000000000000000000000000000000000000000000000000000@https://github.com/gibson9583/oie-node-monitor/releases/download/v0.1.0/nodemonitor-0.1.0.zip,\
sha256:0000000000000000000000000000000000000000000000000000000000000000@https://github.com/gibson9583/oie-key-store/releases/download/v0.1.0/keystore-0.1.0.zip,\
sha256:0000000000000000000000000000000000000000000000000000000000000000@https://github.com/gibson9583/oie-git-sync/releases/download/v0.1.0/gitsync-0.1.0.zip,\
sha256:0000000000000000000000000000000000000000000000000000000000000000@https://github.com/gibson9583/oie-fhir-connector/releases/download/v0.1.0/fhir-0.1.0.zip,\
sha256:0000000000000000000000000000000000000000000000000000000000000000@https://github.com/gibson9583/oie-cluster/releases/download/v0.1.0/cluster-0.1.0.zip,\
sha256:0000000000000000000000000000000000000000000000000000000000000000@https://github.com/gibson9583/oie-backup/releases/download/v0.1.0/backup-0.1.0.zip"

# Trim the Administrator client jars (~82MB, only needed if operators launch the
# Administrator GUI from this server) and the mccommand CLI (~32MB, superseded
# by the REST API for config management).
ARG INCLUDE_ADMIN_CLIENT=true
ARG INCLUDE_CLI=false

RUN apk add --no-cache curl tar unzip

RUN set -eu; \
    url="${OIE_TARBALL_URL}"; \
    if [ -z "$url" ]; then \
        url="https://github.com/OpenIntegrationEngine/engine/releases/download/v${OIE_VERSION}/oie_unix_$(echo "${OIE_VERSION}" | tr '.' '_').tar.gz"; \
    fi; \
    echo "Fetching ${url}"; \
    curl --fail --silent --show-error --location --retry 3 -o /tmp/oie.tar.gz "$url"; \
    if [ -n "${OIE_SHA256}" ]; then \
        echo "${OIE_SHA256}  /tmp/oie.tar.gz" | sha256sum -c -; \
    else \
        echo "WARNING: OIE_SHA256 is empty, skipping integrity check" >&2; \
    fi; \
    mkdir -p /opt/engine; \
    tar -xzf /tmp/oie.tar.gz -C /opt/engine --strip-components=1; \
    rm /tmp/oie.tar.gz

# Fetch this stack's own first-party plugins and unpack them straight into
# extensions.dist/, so they ship baked into the pristine copy below and behave
# like bundled extensions on every boot (see OIE_BUILTIN_PLUGIN_URLS comment
# above for why this is a build-time step rather than the entrypoint's
# runtime OIE_EXTENSION_URLS mechanism).
RUN set -eu; \
    mkdir -p /opt/engine/extensions; \
    idx=0; \
    for entry in $(echo "${OIE_BUILTIN_PLUGIN_URLS}" | tr ',' ' '); do \
        idx=$((idx + 1)); \
        [ -n "$entry" ] || continue; \
        case "$entry" in \
            sha256:*) \
                expected="${entry#sha256:}"; expected="${expected%%@*}"; \
                url="${entry#sha256:${expected}@}" ;; \
            *) \
                expected=""; url="$entry" ;; \
        esac; \
        echo "Fetching built-in plugin ${idx} from ${url}"; \
        curl --fail --silent --show-error --location --retry 3 -o "/tmp/plugin-${idx}.zip" "$url"; \
        if [ -n "$expected" ] && [ "$expected" != "0000000000000000000000000000000000000000000000000000000000000000" ]; then \
            echo "${expected}  /tmp/plugin-${idx}.zip" | sha256sum -c -; \
        else \
            echo "WARNING: no real checksum pinned for built-in plugin ${idx} (${url}) -- placeholder, fill in once a release is cut" >&2; \
        fi; \
        unzip -o -q "/tmp/plugin-${idx}.zip" -d /opt/engine/extensions; \
        rm "/tmp/plugin-${idx}.zip"; \
    done

RUN set -eu; \
    cd /opt/engine; \
    # Windows / service launchers are dead weight in a Linux container.
    rm -f oieserver.ps1 oieservice oieservice.vmoptions; \
    if [ "${INCLUDE_CLI}" != "true" ]; then rm -rf cli-lib oiecommand mirth-cli-launcher.jar; fi; \
    if [ "${INCLUDE_ADMIN_CLIENT}" != "true" ]; then rm -rf client-lib; fi; \
    # Pristine copies: the entrypoint rebuilds conf/ and extensions/ from these on
    # every boot so the running config is a pure function of the environment.
    # extensions/ already includes the built-in plugins fetched above.
    cp -a conf conf.dist; \
    cp -a extensions extensions.dist; \
    # webapps/ is where MirthWebServer scans for *.war at startup and mounts
    # each at /<warname> -- that is how the OIE web administrator is served.
    # The release tarball does not ship the directory; create it so an
    # extension can drop a WAR in without needing to mkdir as a side effect.
    mkdir -p appdata custom-extensions logs server-launcher-lib webapps

########################################################################
# Stage 2: runtime
########################################################################
FROM ${JRE_IMAGE} AS runtime

LABEL org.opencontainers.image.title="Open Integration Engine" \
      org.opencontainers.image.source="https://github.com/OpenIntegrationEngine/engine" \
      org.opencontainers.image.licenses="MPL-2.0"

ARG OIE_VERSION=4.6.0
LABEL org.opencontainers.image.version="${OIE_VERSION}"

RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends ca-certificates curl unzip tzdata; \
    rm -rf /var/lib/apt/lists/*

# The noble base ships an unprivileged uid/gid 1000 named "ubuntu"; rename it to
# "engine" so bind-mounted host directories owned by 1000 line up.
RUN set -eux; \
    groupmod -n engine ubuntu; \
    usermod -l engine -d /opt/engine ubuntu

COPY --from=fetch --chown=engine:engine /opt/engine /opt/engine
COPY --chown=root:root entrypoint.sh /usr/local/bin/oie-entrypoint

RUN chmod 0755 /usr/local/bin/oie-entrypoint /opt/engine/oieserver

ENV HOME=/opt/engine \
    OIE_VERSION=${OIE_VERSION} \
    DATABASE=postgres \
    OIE_HEAP_MAX=1g

WORKDIR /opt/engine

# appdata holds the keystore, the configuration map and the Derby dir (unused
# with Postgres); logs holds the rolling mirth.log. Everything else is immutable.
VOLUME ["/opt/engine/appdata"]

# 8443 admin/REST API (HTTPS), 8080 admin/REST API (HTTP, disabled by default).
# Channel listener ports are whatever your channels bind and must be published
# explicitly in compose.
EXPOSE 8443

USER engine

HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=6 \
    CMD curl -fsSk -o /dev/null -H 'X-Requested-With: healthcheck' \
        "https://127.0.0.1:${HTTPS_PORT:-8443}/api/server/status"

ENTRYPOINT ["/usr/local/bin/oie-entrypoint"]
CMD ["./oieserver"]
