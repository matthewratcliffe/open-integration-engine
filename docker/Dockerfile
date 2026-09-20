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
# Pinned to each repo's latest tagged release as of 2026-09-20 (owner:
# matthewratcliffe, not the placeholder org used before these repos had real
# releases). Bump a version/checksum pair the same way OIE_SHA256 above is
# sourced for the engine itself: the digest comes from that release's own
# asset checksum.
ARG OIE_BUILTIN_PLUGIN_URLS="sha256:ec850661df0512cef4591d9439a321c2be2d8e80c22f0417edbfd04b99d33c60@https://github.com/matthewratcliffe/oie-null-connector/releases/download/v0.2.0/nullsender-0.2.0.zip,\
sha256:b2c2402d8475591effb354134e1e8fc4528ee9c2105c124de5d77e1338207e6e@https://github.com/matthewratcliffe/oie-volume-monitor/releases/download/v0.1.1/volumemonitor-0.1.1.zip,\
sha256:4cba922267521b6ee8bafd37a6265c50c111d356e9f4574b97b4e07178814c34@https://github.com/matthewratcliffe/oie-update-check/releases/download/v0.1.1/updatecheck-0.1.1.zip,\
sha256:b09211f20e65b3cfa6231d80782baef8e36891b67cffba3f27fc69a844a853a1@https://github.com/matthewratcliffe/oie-sso-user-guard/releases/download/v0.1.1/ssouserguard-0.1.1.zip,\
sha256:8792ca72faf3d802abd182e8ff62c8ffaf275f5c45e059c793b7ce43caf45d6f@https://github.com/matthewratcliffe/oie-sftp-connector/releases/download/v0.1.1/sftp-0.1.1.zip,\
sha256:5177bae67b21115f9046481193eeb4fbac15445c87bdaaa4f60a7ff124d6ab59@https://github.com/matthewratcliffe/oie-random-generator/releases/download/v0.1.1/generator-0.1.1.zip,\
sha256:44752bda42bea5d6ccace69f74cba446470c162a412be46b34a9f95d01d241db@https://github.com/matthewratcliffe/oie-node-monitor/releases/download/v0.1.1/nodemonitor-0.1.1.zip,\
sha256:6daab20dbf30648ea5a38656eb6a522595764b22f1cf18807eb8a1f17914d463@https://github.com/matthewratcliffe/oie-key-store/releases/download/v0.1.1/keystore-0.1.1.zip,\
sha256:c75b651e3ab8ab9c756516a3e308dca3a0e23c5ba6dfc27ab945d2e8c96379a2@https://github.com/matthewratcliffe/oie-git-sync/releases/download/v0.1.1/gitsync-0.1.1.zip,\
sha256:bd0802f87c57a9dd1dc75677607115360935e5267316b79c38dd022396722a03@https://github.com/matthewratcliffe/oie-fhir-connector/releases/download/v0.2.1/fhir-0.2.1.zip,\
sha256:39bcbf226344e1397ce9285df048aee65c6630f2a0ba13457b7caa73a6f657b1@https://github.com/matthewratcliffe/oie-cluster/releases/download/v0.1.1/cluster-0.1.1.zip,\
sha256:00564877079f078ed1da1ba8821811d14c48301320b2fc98d183fea93e0bde62@https://github.com/matthewratcliffe/oie-backup/releases/download/v0.1.1/backup-0.1.1.zip"

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
