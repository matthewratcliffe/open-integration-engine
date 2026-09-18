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

# Trim the Administrator client jars (~82MB, only needed if operators launch the
# Administrator GUI from this server) and the mccommand CLI (~32MB, superseded
# by the REST API for config management).
ARG INCLUDE_ADMIN_CLIENT=true
ARG INCLUDE_CLI=false

RUN apk add --no-cache curl tar

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

RUN set -eu; \
    cd /opt/engine; \
    # Windows / service launchers are dead weight in a Linux container.
    rm -f oieserver.ps1 oieservice oieservice.vmoptions; \
    if [ "${INCLUDE_CLI}" != "true" ]; then rm -rf cli-lib oiecommand mirth-cli-launcher.jar; fi; \
    if [ "${INCLUDE_ADMIN_CLIENT}" != "true" ]; then rm -rf client-lib; fi; \
    # Pristine copies: the entrypoint rebuilds conf/ and extensions/ from these on
    # every boot so the running config is a pure function of the environment.
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
