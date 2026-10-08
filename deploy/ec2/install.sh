#!/usr/bin/env bash
#
# Installs Open Integration Engine directly on an EC2 host -- no Docker --
# against a PostgreSQL database on RDS, configured entirely from the
# environment. It runs from the bundle deploy/ec2/package.sh builds, which
# carries the release and plugins, so the instance needs no GitHub or GitLab
# access. The deploy-ec2 GitLab job puts the bundle in /opt/oie/bundle and runs
# it through SSM (deploy/ec2/deploy.sh); by hand, as root:
#
#   OIE_SSM_PATH=/oie/production/oie1 /opt/oie/bundle/install.sh
#
# Re-running it is how you change anything: it rewrites /etc/oie/oie.env from
# the current environment, reinstalls the release only when the bundle's
# release or plugins changed, and restarts the engine. appdata/ (the keystore)
# and logs/ are never touched.
#
# What it sets up:
#   * Java 21
#   * the engine's role and database on RDS, when given the master credentials
#   * the release tarball and this stack's 13 first-party plugins, checksum
#     verified, in /opt/engine -- the same layout the Docker image has
#   * docker/entrypoint.sh, unchanged, as the oie systemd service's start
#     command, so every variable documented in .env.example means the same here
#   * nginx on 443 in front of the engine, which listens on loopback only, and
#     on 80 redirecting to it; with oie-gate.py beside it, the API docs for SSO
#     accounts only and, optionally, no password sign-in on the web console
#   * the admin password rotation that the compose `bootstrap` service does
#
# Configuration comes from SSM: set OIE_SSM_PATH and every SSM parameter
# under that path is loaded as a variable named after its last path segment
# (/oie/prod/DATABASE_PASSWORD -> DATABASE_PASSWORD). A variable already in the
# environment wins. Needs the instance role to allow ssm:GetParametersByPath
# (and kms:Decrypt for SecureString parameters under a customer key).

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_DIR=/opt/engine
INSTALL_ENV=/etc/oie/install.env
ENV_FILE=/etc/oie/oie.env
SCRIPTS_DIR=/opt/oie/scripts
START_SCRIPT=/usr/local/libexec/oie/start
UNIT=/etc/systemd/system/oie.service

log() { printf '%s [install] %s\n' "$(date -u '+%Y-%m-%d %H:%M:%S')" "$*"; }
die() { log "ERROR: $*"; exit 1; }

[[ $EUID -eq 0 ]] || die "run as root"
[[ -r "$HERE/release.env" && -d "$HERE/payload" ]] \
    || die "run this from a bundle built by deploy/ec2/package.sh, not from the repo"
# shellcheck source=/dev/null
. "$HERE/release.env"

########################################################################
# OS
########################################################################
. /etc/os-release
case "${ID}" in
    amzn)          PKG=dnf ;;
    ubuntu|debian) PKG=apt ;;
    *) die "unsupported OS ${ID}: use Amazon Linux 2023 or Ubuntu" ;;
esac

pkg_install() {
    if [[ $PKG == dnf ]]; then
        dnf install -y -q "$@"
    else
        DEBIAN_FRONTEND=noninteractive apt-get install -y -qq "$@"
    fi
}

[[ $PKG == apt ]] && apt-get update -qq
# AL2023 ships curl-minimal, which conflicts with the curl package and does
# everything needed here, so only ask for curl where there is none.
command -v curl >/dev/null || pkg_install curl
pkg_install tar unzip jq openssl
if [[ $PKG == dnf ]]; then
    pkg_install java-21-amazon-corretto-headless "postgresql${POSTGRES_MAJOR:-16}" \
        shadow-utils findutils gzip util-linux
    command -v aws >/dev/null || pkg_install awscli-2
else
    pkg_install openjdk-21-jre-headless ca-certificates postgresql-client
    # Ubuntu has no aws CLI package; only needed for OIE_SSM_PATH.
    if [[ -n "${OIE_SSM_PATH:-}" ]] && ! command -v aws >/dev/null; then
        snap install aws-cli --classic
    fi
fi

########################################################################
# Secrets from SSM Parameter Store
########################################################################
# Remembered from the last run, so a re-run by hand needs no arguments.
if [[ -z "${OIE_SSM_PATH:-}" && -r "$INSTALL_ENV" ]]; then
    # shellcheck source=/dev/null
    . "$INSTALL_ENV"
fi
if [[ -n "${OIE_SSM_PATH:-}" && -z "${AWS_REGION:-}" ]]; then
    # The instance's own region, from the metadata service (IMDSv2).
    imds_token="$(curl -fsS -X PUT -H 'X-aws-ec2-metadata-token-ttl-seconds: 60' \
        http://169.254.169.254/latest/api/token)" || die "set AWS_REGION: the metadata service did not answer"
    AWS_REGION="$(curl -fsS -H "X-aws-ec2-metadata-token: ${imds_token}" \
        http://169.254.169.254/latest/meta-data/placement/region)"
    export AWS_REGION
fi
if [[ -n "${OIE_SSM_PATH:-}" ]]; then
    install -d -m 0755 "$(dirname "$INSTALL_ENV")"
    printf 'OIE_SSM_PATH=%q\nAWS_REGION=%q\n' "$OIE_SSM_PATH" "$AWS_REGION" > "$INSTALL_ENV"
    log "loading parameters under ${OIE_SSM_PATH}"
    params="$(aws ssm get-parameters-by-path --path "$OIE_SSM_PATH" \
        --recursive --with-decryption --output json)" \
        || die "could not read ${OIE_SSM_PATH} from SSM -- check the instance role"
    while IFS= read -r -d '' name && IFS= read -r -d '' value; do
        name="${name##*/}"
        if [[ ! "$name" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]]; then
            log "skipping parameter ${name}: not a variable name"
            continue
        fi
        if [[ -n "${!name:-}" ]]; then
            log "${name} already set in the environment, ignoring the parameter"
            continue
        fi
        export "${name}=${value}"
        log "loaded ${name}"
    done < <(jq -j '.Parameters[] | .Name, "\u0000", .Value, "\u0000"' <<<"$params")
fi

# Single sign-on pinned from GitLab: OIE_OIDC_SETTINGS is a JSON object of
# OIE_OIDC_* overrides, rewritten whole on every deploy (publish-params.sh).
# Non-empty, it owns those names, as the ECS task's environment does: one it
# leaves out is unset, since the extension reads even an empty one as an
# override. {} pins nothing, and OIE_OIDC_* parameters put in SSM by hand apply.
OIDC_PINNED=(OIE_OIDC_ENABLED OIE_OIDC_WEB_ADMINISTRATOR_URL OIE_OIDC_DISCOVERY_URL
    OIE_OIDC_CLIENT_ID OIE_OIDC_CLIENT_SECRET OIE_OIDC_PROVIDER_LABEL
    OIE_OIDC_USERNAME_CLAIM OIE_OIDC_SCOPES OIE_OIDC_AUTO_REDIRECT OIE_OIDC_JIT_ENABLED)
if [[ -n "${OIE_OIDC_SETTINGS:-}" ]]; then
    jq -e 'type == "object" and all(.[]; type == "string")' <<<"$OIE_OIDC_SETTINGS" >/dev/null 2>&1 \
        || die "OIE_OIDC_SETTINGS is not a JSON object of strings"
    if [[ "$(jq length <<<"$OIE_OIDC_SETTINGS")" -gt 0 ]]; then
        unset "${OIDC_PINNED[@]}"
        while IFS= read -r -d '' name && IFS= read -r -d '' value; do
            [[ " ${OIDC_PINNED[*]} " == *" ${name} "* ]] || die "OIE_OIDC_SETTINGS holds ${name}, which it does not manage"
            export "${name}=${value}"
        done < <(jq -j 'to_entries[] | .key, "\u0000", .value, "\u0000"' <<<"$OIE_OIDC_SETTINGS")
        log "SSO pinned from GitLab: $(jq -r 'del(.OIE_OIDC_CLIENT_SECRET) | keys | join(" ")' <<<"$OIE_OIDC_SETTINGS")"
    fi
fi

########################################################################
# Inputs
########################################################################
# require <name> <what it is> -- says where the value was expected, since it
# usually comes from a GitLab variable by way of SSM.
require() {
    [[ -n "${!1:-}" ]] && return 0
    if [[ -n "${OIE_SSM_PATH:-}" ]]; then
        die "$1 is not set ($2). Set it as a GitLab variable in the deploy job's environment -- the deploy copies it to ${OIE_SSM_PATH}/$1 -- or put that SSM parameter in yourself"
    fi
    die "$1 is not set ($2)"
}
require OIE_ADMIN_PASSWORD "the admin account's password"
# Guards appdata/keystore.jks, which also holds the data-encryption key.
# Changing it after first boot makes the keystore unreadable, so there is no
# default to fall back on by accident.
require KEYSTORE_PASSWORD "guards the keystore; it cannot change after first boot"

# Either the whole JDBC URL, or the RDS endpoint and a database name.
POSTGRES_DB="${POSTGRES_DB:-mirthdb}"
if [[ -z "${DATABASE_URL:-}" ]]; then
    require RDS_ENDPOINT "the RDS instance host, optionally :port; or set DATABASE_URL"
    [[ "$RDS_ENDPOINT" == *:* ]] || RDS_ENDPOINT="${RDS_ENDPOINT}:5432"
    DATABASE_URL="jdbc:postgresql://${RDS_ENDPOINT}/${POSTGRES_DB}?sslmode=require"
fi
[[ "$DATABASE_URL" =~ ^jdbc:postgresql://([^/:?]+)(:([0-9]+))?/([^?]+) ]] \
    || die "DATABASE_URL must look like jdbc:postgresql://host[:port]/database"
DB_HOST="${BASH_REMATCH[1]}"
DB_PORT="${BASH_REMATCH[3]:-5432}"
DB_NAME="${BASH_REMATCH[4]}"
# POSTGRES_USER/POSTGRES_PASSWORD are accepted too, as .env spells them.
DATABASE_USERNAME="${DATABASE_USERNAME:-${POSTGRES_USER:-mirthdb}}"
DATABASE_PASSWORD="${DATABASE_PASSWORD:-${POSTGRES_PASSWORD:-}}"
require DATABASE_PASSWORD "the password for the engine's database user, ${DATABASE_USERNAME}"

# nginx serves the console and API on HTTPS_PORT and proxies to the engine,
# which listens on loopback only (ENGINE_PORT), so nothing reaches it without
# passing the proxy. Port 80 only redirects -- not the engine's own plaintext
# listener (HTTP_PORT), which stays off.
HTTPS_PORT="${HTTPS_PORT:-443}"
ENGINE_PORT=8443
GATE_PORT=8441
HTTP_REDIRECT="${HTTP_REDIRECT:-true}"
# Password sign-in on the web administrator; false leaves it SSO only.
WEB_LOCAL_LOGIN="${WEB_LOCAL_LOGIN:-true}"
# The API documentation (Swagger UI, the OpenAPI spec, javadocs) only for a
# signed-in SSO account; false serves it to anyone, as the engine does.
API_DOCS_REQUIRE_SSO="${API_DOCS_REQUIRE_SSO:-true}"
[[ "$HTTPS_PORT" =~ ^[0-9]+$ ]] && ! [[ " 80 ${ENGINE_PORT} ${GATE_PORT} " == *" ${HTTPS_PORT} "* ]] \
    || die "HTTPS_PORT must be a port number other than 80, ${ENGINE_PORT} and ${GATE_PORT}, not ${HTTPS_PORT}"
for flag in HTTP_REDIRECT WEB_LOCAL_LOGIN API_DOCS_REQUIRE_SSO; do
    [[ "${!flag}" == true || "${!flag}" == false ]] || die "${flag} must be true or false, not ${!flag}"
done
if [[ "$HTTP_REDIRECT" == true && "${HTTP_PORT:-0}" == 80 ]]; then
    die "HTTP_PORT=80 puts the engine's plaintext listener where the HTTPS redirect goes: set HTTP_REDIRECT=false too, or leave HTTP_PORT off"
fi

# The release and plugins are whatever the bundle carries (release.env).
INCLUDE_ADMIN_CLIENT="${INCLUDE_ADMIN_CLIENT:-true}"
INCLUDE_CLI="${INCLUDE_CLI:-false}"
OIE_UPDATE_CHECK_EXTENSIONS="${OIE_UPDATE_CHECK_EXTENSIONS:-${OIE_UPDATE_CHECK_EXTENSIONS_DEFAULT}}"
log "bundle: OIE ${OIE_VERSION}, commit ${OIE_BUNDLE_COMMIT}"

########################################################################
# The database on RDS
########################################################################
# With the master credentials, make sure the engine's role and database exist
# -- the same job as the ECS db-init task (infra/aws/database.tf). Without them
# both must already be there. The engine creates its own schema on first boot.
if [[ -n "${RDS_MASTER_USERNAME:-}" && -n "${RDS_MASTER_PASSWORD:-}" ]]; then
    log "ensuring role ${DATABASE_USERNAME} and database ${DB_NAME} on ${DB_HOST}:${DB_PORT}"
    # Values go through psql variables, so %I/%L quote them and a password
    # cannot break out of the statement whatever it contains. The master user
    # is made a member of the engine's role because RDS's master is not a
    # superuser, and CREATE DATABASE ... OWNER needs that membership.
    PGHOST="$DB_HOST" PGPORT="$DB_PORT" PGDATABASE=postgres PGSSLMODE=require \
    PGUSER="$RDS_MASTER_USERNAME" PGPASSWORD="$RDS_MASTER_PASSWORD" PGCONNECT_TIMEOUT=15 \
        psql -v ON_ERROR_STOP=1 -qtA \
            -v user="$DATABASE_USERNAME" -v pass="$DATABASE_PASSWORD" -v db="$DB_NAME" <<'SQL' \
        || die "could not prepare ${DB_NAME} on ${DB_HOST} -- check the RDS security group allows this instance on ${DB_PORT}, and the master credentials"
SELECT format('CREATE ROLE %I LOGIN', :'user') WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = :'user')\gexec
SELECT format('ALTER ROLE %I PASSWORD %L', :'user', :'pass') WHERE :'user' <> current_user\gexec
SELECT format('GRANT %I TO %I', :'user', current_user) WHERE :'user' <> current_user\gexec
SELECT format('CREATE DATABASE %I OWNER %I', :'db', :'user') WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = :'db')\gexec
SQL
else
    log "RDS_MASTER_USERNAME/RDS_MASTER_PASSWORD not set: expecting ${DB_NAME} and ${DATABASE_USERNAME} to exist already"
fi

# Fail here, with a reason, rather than as a crash loop in the journal.
PGHOST="$DB_HOST" PGPORT="$DB_PORT" PGDATABASE="$DB_NAME" PGSSLMODE=require \
PGUSER="$DATABASE_USERNAME" PGPASSWORD="$DATABASE_PASSWORD" PGCONNECT_TIMEOUT=15 \
    psql -qtA -c 'SELECT 1' >/dev/null \
    || die "${DATABASE_USERNAME} cannot connect to ${DB_NAME} on ${DB_HOST}:${DB_PORT}"
log "database ${DB_NAME} on ${DB_HOST} is reachable as ${DATABASE_USERNAME}"

########################################################################
# The engine user and the release
########################################################################
if ! id engine >/dev/null 2>&1; then
    useradd --system --home-dir "$APP_DIR" --no-create-home --shell /sbin/nologin engine
fi
install -d -o engine -g engine "$APP_DIR"

# Refuse a bundle damaged or altered on the way; package.sh verified each file
# against its pinned checksum before writing these.
(cd "$HERE/payload" && sha256sum -c --quiet SHA256SUMS) \
    || die "the bundle's payload does not match its SHA256SUMS"

# Everything that decides the contents of the immutable part of /opt/engine.
release_stamp="$(printf '%s\n' "$(sha256sum "$HERE/payload/SHA256SUMS")" \
    "$INCLUDE_ADMIN_CLIENT" "$INCLUDE_CLI" | sha256sum | cut -d' ' -f1)"

STAGE=""
trap '[[ -z "$STAGE" ]] || rm -rf "$STAGE"' EXIT

install_release() {
    STAGE="$(mktemp -d /var/tmp/oie-release.XXXXXX)"
    local stage="$STAGE"

    mkdir -p "$stage/engine"
    tar -xzf "$HERE/payload/oie.tar.gz" -C "$stage/engine" --strip-components=1

    # Same as the Dockerfile: built-in plugins land in extensions/ before the
    # pristine copy is taken, so they survive the entrypoint's per-boot reset.
    local zip idx=0
    mkdir -p "$stage/engine/extensions"
    for zip in "$HERE"/payload/plugins/*.zip; do
        [[ -e "$zip" ]] || continue
        idx=$((idx + 1))
        unzip -o -q "$zip" -d "$stage/engine/extensions"
    done

    (
        cd "$stage/engine"
        rm -f oieserver.ps1 oieservice oieservice.vmoptions
        [[ "$INCLUDE_CLI" == true ]] || rm -rf cli-lib oiecommand mirth-cli-launcher.jar
        [[ "$INCLUDE_ADMIN_CLIENT" == true ]] || rm -rf client-lib
        cp -a conf conf.dist
        cp -a extensions extensions.dist
        mkdir -p appdata custom-extensions logs server-launcher-lib webapps
        chmod 0755 oieserver
    )

    systemctl stop oie 2>/dev/null || true
    # Replace everything but the state: appdata holds the keystore and its
    # data-encryption key, logs the history, custom-extensions is synced below.
    find "$APP_DIR" -mindepth 1 -maxdepth 1 \
        ! -name appdata ! -name logs ! -name custom-extensions -exec rm -rf {} +
    cp -a "$stage/engine/." "$APP_DIR/"
    echo "$release_stamp" > "$APP_DIR/.release"
    log "installed OIE ${OIE_VERSION} with ${idx} built-in plugins"
}

if [[ "$(cat "$APP_DIR/.release" 2>/dev/null || true)" != "$release_stamp" ]]; then
    install_release
else
    log "OIE ${OIE_VERSION} already installed with this plugin list"
fi

# What compose mounts from ./extensions: licensed zips and the web admin overlay.
mkdir -p "$APP_DIR/custom-extensions"
find "$APP_DIR/custom-extensions" -mindepth 1 -delete
find "$HERE/extensions" -mindepth 1 -maxdepth 1 \
    -exec cp -a {} "$APP_DIR/custom-extensions/" \;
# Plus the community extensions (Web Support, OIDC auth, ...) that compose and
# ECS download at boot through OIE_EXTENSION_URLS: package.sh bundled them, as
# this instance has no GitHub access.
find "$HERE/payload/extensions" -mindepth 1 -maxdepth 1 -name '*.zip' \
    -exec cp -a {} "$APP_DIR/custom-extensions/" \;
log "custom extensions: $(find "$APP_DIR/custom-extensions" -mindepth 1 -maxdepth 1 -name '*.zip' -printf '%f\n' | sort | paste -sd' ' -)"
chown -R engine:engine "$APP_DIR"

########################################################################
# TLS certificate
########################################################################
# TLS_CERT_PEM/TLS_KEY_PEM are the shared *.htrak.com wildcard, the GitLab
# variables deploy-ec2 copies into SSM (deploy/ec2/publish-params.sh). They are PEM, so they
# cannot travel through the environment file; they go to files the start
# script reads, and it puts them in the keystore as the engine's own cert.
TLS_DIR=/etc/oie/tls
if [[ -n "${TLS_CERT_PEM:-}" || -n "${TLS_KEY_PEM:-}" ]]; then
    [[ -n "${TLS_CERT_PEM:-}" && -n "${TLS_KEY_PEM:-}" ]] \
        || die "set both TLS_CERT_PEM and TLS_KEY_PEM, or neither"
    install -d -m 0750 -o root -g engine "$TLS_DIR"
    printf '%s\n' "$TLS_CERT_PEM" > "$TLS_DIR/cert.pem.new"
    ( umask 027; printf '%s\n' "$TLS_KEY_PEM" > "$TLS_DIR/key.pem.new" )
    cert_pub="$(openssl x509 -in "$TLS_DIR/cert.pem.new" -noout -pubkey 2>/dev/null)" \
        || die "TLS_CERT_PEM is not a PEM certificate"
    key_pub="$(openssl pkey -in "$TLS_DIR/key.pem.new" -pubout 2>/dev/null)" \
        || die "TLS_KEY_PEM is not an unencrypted PEM private key"
    [[ "$cert_pub" == "$key_pub" ]] || die "TLS_KEY_PEM is not the key for TLS_CERT_PEM"
    openssl x509 -in "$TLS_DIR/cert.pem.new" -noout -checkend $((14 * 86400)) >/dev/null \
        || log "WARNING: the TLS certificate expires within 14 days"
    chown root:engine "$TLS_DIR/cert.pem.new" "$TLS_DIR/key.pem.new"
    chmod 0644 "$TLS_DIR/cert.pem.new"
    chmod 0640 "$TLS_DIR/key.pem.new"
    mv "$TLS_DIR/cert.pem.new" "$TLS_DIR/cert.pem"
    mv "$TLS_DIR/key.pem.new" "$TLS_DIR/key.pem"
    log "TLS certificate: $(openssl x509 -in "$TLS_DIR/cert.pem" -noout -subject -enddate | tr '\n' ' ')"
elif [[ -e "$TLS_DIR/cert.pem" ]]; then
    # The keystore keeps whatever was imported last; this only stops the start
    # script importing it again.
    find "$TLS_DIR" -mindepth 1 -delete
    log "TLS_CERT_PEM not set: no longer importing a certificate (the keystore keeps its current one)"
fi

########################################################################
# Start script, scripts, environment, service
########################################################################
install -D -m 0755 "$HERE/entrypoint.sh" /usr/local/bin/oie-entrypoint
install -d -m 0755 "$SCRIPTS_DIR"
install -m 0755 "$HERE"/scripts/*.sh "$SCRIPTS_DIR/"

# The keystore handling the ECS task does before the entrypoint (infra/aws/
# locals.tf): KEYSTORE_RESET deliberately replaces it, KEYSTORE_BASE64 seeds it
# only when there is none yet, since after first boot it also holds the
# engine's data-encryption key.
install -d -m 0755 "$(dirname "$START_SCRIPT")"
cat > "$START_SCRIPT" <<'EOF'
#!/usr/bin/env bash
set -eu
appdata=/opt/engine/appdata
if [[ -n "${KEYSTORE_RESET:-}" && "$(cat "$appdata/.keystore-reset" 2>/dev/null || true)" != "$KEYSTORE_RESET" ]]; then
  if [[ -e "$appdata/keystore.jks" ]]; then
    mv "$appdata/keystore.jks" "$appdata/keystore.jks.replaced-$KEYSTORE_RESET"
    echo "[keystore] reset $KEYSTORE_RESET: moved keystore.jks to keystore.jks.replaced-$KEYSTORE_RESET"
  fi
  printf '%s' "$KEYSTORE_RESET" > "$appdata/.keystore-reset"
fi
if [[ -n "${KEYSTORE_BASE64:-}" && ! -s "$appdata/keystore.jks" ]]; then
  printf '%s' "$KEYSTORE_BASE64" | base64 -d > "$appdata/keystore.jks"
  chmod 600 "$appdata/keystore.jks"
  echo "[keystore] installed the supplied keystore"
fi

# The engine serves the cert under alias mirthconnect, and only generates its
# self-signed one when that alias is missing. So put the supplied cert there,
# leaving every other entry -- the data-encryption key above all -- alone.
tls=/etc/oie/tls
ks="$appdata/keystore.jks"
if [[ -e "$tls/cert.pem" && ! ( -r "$tls/cert.pem" && -r "$tls/key.pem" ) ]]; then
  echo "[tls] $tls is not readable by $(id -un); serving the keystore's current certificate" >&2
elif [[ -e "$tls/cert.pem" ]]; then
  case "$(head -c 4 "$ks" 2>/dev/null | od -An -tx1 | tr -d ' \n')" in
    cececece) kstype=JCEKS ;;
    feedfeed) kstype=JKS ;;
    *)        kstype="${KEYSTORE_TYPE:-JCEKS}" ;;
  esac
  fingerprint() { openssl x509 -noout -fingerprint -sha256 2>/dev/null | cut -d= -f2; }
  want="$(fingerprint < "$tls/cert.pem")"
  have="$(keytool -exportcert -rfc -alias mirthconnect -keystore "$ks" -storetype "$kstype" \
            -storepass:env KEYSTORE_STOREPASS 2>/dev/null | fingerprint || true)"
  if [[ "$want" != "$have" ]]; then
    p12="$(mktemp)"
    trap 'rm -f "$p12"' EXIT
    [[ -s "$ks" ]] && cp -p "$ks" "$ks.before-tls"
    if openssl pkcs12 -export -in "$tls/cert.pem" -inkey "$tls/key.pem" -name mirthconnect \
            -out "$p12" -passout env:KEYSTORE_KEYPASS \
        && { [[ -z "$have" ]] || keytool -delete -alias mirthconnect -keystore "$ks" \
                -storetype "$kstype" -storepass:env KEYSTORE_STOREPASS; } \
        && keytool -importkeystore -noprompt \
            -srckeystore "$p12" -srcstoretype PKCS12 -srcstorepass:env KEYSTORE_KEYPASS -srcalias mirthconnect \
            -destkeystore "$ks" -deststoretype "$kstype" -deststorepass:env KEYSTORE_STOREPASS \
            -destkeypass:env KEYSTORE_KEYPASS -destalias mirthconnect >/dev/null; then
      chmod 600 "$ks"
      echo "[tls] engine certificate is now $(openssl x509 -in "$tls/cert.pem" -noout -subject)"
    else
      # Never start on a half-written keystore: put the previous one back.
      [[ -e "$ks.before-tls" ]] && cp -p "$ks.before-tls" "$ks"
      echo "[tls] could not import /etc/oie/tls into the keystore; keeping the previous certificate" >&2
    fi
  fi
fi

cd /opt/engine
exec /usr/local/bin/oie-entrypoint ./oieserver
EOF
chmod 0755 "$START_SCRIPT"

# env_line NAME VALUE -- one systemd EnvironmentFile assignment. Double quotes
# keep spaces and quotes intact; systemd does no $ expansion in these files.
env_line() {
    [[ "$2" != *$'\n'* ]] || die "$1 contains a newline, which an environment file cannot carry"
    local v="${2//\\/\\\\}"
    printf '%s="%s"\n' "$1" "${v//\"/\\\"}"
}

# The same mapping compose.yaml makes from .env to the engine's environment.
render_env() {
    env_line DATABASE postgres
    env_line DATABASE_URL "$DATABASE_URL"
    env_line DATABASE_USERNAME "$DATABASE_USERNAME"
    env_line DATABASE_PASSWORD "$DATABASE_PASSWORD"
    env_line DATABASE_MAX_CONNECTIONS "${DATABASE_MAX_CONNECTIONS:-20}"
    env_line DATABASE_READONLY_MAX_CONNECTIONS "${DATABASE_READONLY_MAX_CONNECTIONS:-20}"
    env_line DATABASE_MAX_RETRY "${DATABASE_MAX_RETRY:-10}"
    env_line DATABASE_RETRY_WAIT "${DATABASE_RETRY_WAIT:-5000}"
    env_line KEYSTORE_STOREPASS "$KEYSTORE_PASSWORD"
    env_line KEYSTORE_KEYPASS "$KEYSTORE_PASSWORD"
    env_line _MP_SERVER_INITIALADMINPASSWORD "$OIE_ADMIN_PASSWORD"
    env_line HTTP_PORT "${HTTP_PORT:-0}"
    env_line HTTPS_PORT "$ENGINE_PORT"
    env_line _MP_HTTPS_HOST 127.0.0.1
    env_line _MP_SERVER_API_XFRAMEOPTIONS "${API_XFRAME_OPTIONS:-SAMEORIGIN}"
    env_line _MP_SERVER_API_CONTENTSECURITYPOLICY "${API_CSP:-frame-ancestors 'self'}"
    env_line OIE_UPDATE_CHECK "${OIE_UPDATE_CHECK:-true}"
    env_line OIE_UPDATE_CHECK_EXTENSIONS "$OIE_UPDATE_CHECK_EXTENSIONS"
    env_line OIE_HEAP_MAX "${OIE_HEAP_MAX:-1g}"
    env_line OIE_VERSION "$OIE_VERSION"
    env_line TZ "${TZ:-UTC}"

    # Everything else the entrypoint understands passes straight through:
    # SESSION_STORE, SERVER_ID, VMOPTIONS, KEYSTORE_BASE64,
    # KEYSTORE_RESET, the other _MP_* and OIE_* settings (OIDC, cluster,
    # extension URLs), *_DOWNLOAD, *_FILE, ...
    local name
    while IFS= read -r name; do
        case "$name" in
            # Written above, or only meaningful to this installer.
            DATABASE|DATABASE_URL|DATABASE_USERNAME|DATABASE_PASSWORD|DATABASE_MAX_CONNECTIONS|\
            DATABASE_READONLY_MAX_CONNECTIONS|DATABASE_MAX_RETRY|DATABASE_RETRY_WAIT|\
            KEYSTORE_STOREPASS|KEYSTORE_KEYPASS|KEYSTORE_PASSWORD|_MP_SERVER_INITIALADMINPASSWORD|\
            _MP_SERVER_API_XFRAMEOPTIONS|_MP_SERVER_API_CONTENTSECURITYPOLICY|HTTP_PORT|HTTPS_PORT|\
            OIE_UPDATE_CHECK|OIE_UPDATE_CHECK_EXTENSIONS|OIE_HEAP_MAX|OIE_VERSION|TZ|\
            OIE_ADMIN_PASSWORD|OIE_SSM_PATH|OIE_SHA256|OIE_TARBALL_URL|OIE_BUILTIN_PLUGIN_URLS|\
            OIE_WAIT_TIMEOUT|HTTP_REDIRECT|_MP_HTTPS_HOST|\
            OIE_OIDC_SETTINGS|\
            OIE_EXTENSION_URLS)  # bundled instead, see custom-extensions above
                continue ;;
            OIE_*|_MP_*|KEYSTORE_*|DATABASE*|SESSION_STORE|SERVER_ID|VMOPTIONS|DELAY|*_DOWNLOAD|*_FILE)
                env_line "$name" "${!name}" ;;
        esac
    done < <(compgen -e | sort)
}

# 0755: the engine user has to reach /etc/oie/tls inside it. The files that
# hold secrets are 0600 on their own.
install -d -m 0755 "$(dirname "$ENV_FILE")"
( umask 077; render_env > "${ENV_FILE}.new" )
mv "${ENV_FILE}.new" "$ENV_FILE"
log "wrote ${ENV_FILE}"

cat > "$UNIT" <<EOF
[Unit]
Description=Open Integration Engine
Wants=network-online.target
After=network-online.target

[Service]
User=engine
Group=engine
WorkingDirectory=${APP_DIR}
EnvironmentFile=${ENV_FILE}
ExecStart=${START_SCRIPT}
Restart=on-failure
RestartSec=10
TimeoutStopSec=120
LimitNOFILE=65536
# Channels may listen below 1024 (MLLP on 104, say) without running as root.
AmbientCapabilities=CAP_NET_BIND_SERVICE

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable oie >/dev/null
systemctl restart oie
log "oie.service started -- journalctl -u oie -f to follow it"

########################################################################
# The HTTPS proxy: nginx on HTTPS_PORT and 80, and its gate
########################################################################
# nginx terminates TLS and proxies everything to the engine on loopback. Two
# things it asks oie-gate (deploy/ec2/oie-gate.py) about first:
#   * the API documentation, with API_DOCS_REQUIRE_SSO=true: served only to an
#     engine session whose account is bound to the SSO provider
#   * the web administrator's sign-in, with WEB_LOCAL_LOGIN=false: only the
#     SSO ticket gets through; the REST API and the Swing Administrator keep
#     password sign-in, the break-glass path
# Its config replaces the distribution's whole, so neither Amazon Linux's
# built-in server block nor Ubuntu's default site is served.
command -v nginx >/dev/null || pkg_install nginx
command -v python3 >/dev/null || pkg_install python3

# The wildcard from SSM; without it, a self-signed certificate of the proxy's
# own, made once.
if [[ -s "$TLS_DIR/cert.pem" && -s "$TLS_DIR/key.pem" ]]; then
    proxy_cert="$TLS_DIR/cert.pem" proxy_key="$TLS_DIR/key.pem"
else
    proxy_cert=/etc/oie/proxy/selfsigned.pem proxy_key=/etc/oie/proxy/selfsigned.key
    if [[ ! -s "$proxy_cert" || ! -s "$proxy_key" ]]; then
        install -d -m 0700 /etc/oie/proxy
        openssl req -x509 -newkey rsa:2048 -nodes -days 3650 -subj "/CN=$(hostname -f 2>/dev/null || hostname)" \
            -keyout "$proxy_key" -out "$proxy_cert" 2>/dev/null \
            || die "could not make a self-signed certificate for nginx"
    fi
    log "TLS_CERT_PEM not set: nginx serves a self-signed certificate"
fi

GATE_UNIT=/etc/systemd/system/oie-gate.service
if [[ "$WEB_LOCAL_LOGIN" == false || "$API_DOCS_REQUIRE_SSO" == true ]]; then
    install -D -m 0755 "$HERE/oie-gate.py" /usr/local/libexec/oie/oie-gate
    cat > "$GATE_UNIT" <<EOF
[Unit]
Description=OIE proxy gate (deploy/ec2/oie-gate.py)
After=network.target

[Service]
ExecStart=$(command -v python3) /usr/local/libexec/oie/oie-gate
Environment=OIE_GATE_LISTEN=127.0.0.1:${GATE_PORT}
Environment=OIE_ENGINE_URL=https://127.0.0.1:${ENGINE_PORT}
DynamicUser=yes
NoNewPrivileges=yes
ProtectSystem=strict
ProtectHome=yes
PrivateTmp=yes
Restart=always
RestartSec=2

[Install]
WantedBy=multi-user.target
EOF
    systemctl daemon-reload
    systemctl enable oie-gate >/dev/null
    systemctl restart oie-gate
elif [[ -e "$GATE_UNIT" ]]; then
    systemctl disable --now oie-gate >/dev/null 2>&1 || true
fi

if [[ "$WEB_LOCAL_LOGIN" == false && "${OIE_OIDC_ENABLED:-}" != true ]]; then
    log "WARNING: WEB_LOCAL_LOGIN=false but SSO is not pinned on (OIDC_ENABLED=true): unless it is configured in the console, nobody can sign in to the web administrator"
fi

engine_upstream="https://127.0.0.1:${ENGINE_PORT}"
docs_locations=""
if [[ "$API_DOCS_REQUIRE_SSO" == true ]]; then
    # Swagger UI and its assets, the OpenAPI spec, the examples it fetches and
    # the javadocs: every path the engine serves documentation on, and no API
    # resource (MirthWebServer: public_api_html at /api, openapi.* there).
    docs_locations="$(cat <<EOF

        # API documentation: SSO accounts only (oie-gate /docs). No session
        # goes to the sign-in page; a local account's gets a 403.
        location ~ ^/api(/|/index\.html|/(css|fonts|images|lang|lib)/.*|/openapi\.(json|yaml))?\$ {
            auth_request /_gate/docs;
            error_page 401 = @signin;
            error_page 403 = @sso_required;
            proxy_pass ${engine_upstream};
        }
        location ~ ^/(apiexamples|javadocs)(/.*)?\$ {
            auth_request /_gate/docs;
            error_page 401 = @signin;
            error_page 403 = @sso_required;
            proxy_pass ${engine_upstream};
        }
        location = /_gate/docs {
            internal;
            proxy_pass http://127.0.0.1:${GATE_PORT}/docs;
            proxy_pass_request_body off;
            proxy_set_header Content-Length "";
        }
        location @signin {
            return 302 /oie-webadmin/;
        }
        location @sso_required {
            default_type text/plain;
            return 403 "The API documentation is for SSO accounts. Sign in to the web administrator with SSO, then reload this page.\n";
        }
EOF
)"
fi
login_location=""
if [[ "$WEB_LOCAL_LOGIN" == false ]]; then
    login_location="$(cat <<EOF

        # Sign-in goes through oie-gate, which turns away the web
        # administrator's password sign-in and forwards the rest.
        location = /api/users/_login {
            proxy_pass http://127.0.0.1:${GATE_PORT};
        }
EOF
)"
fi
redirect_server=""
if [[ "$HTTP_REDIRECT" == true ]]; then
    if [[ "$HTTPS_PORT" == 443 ]]; then https_authority='$host'; else https_authority="\$host:${HTTPS_PORT}"; fi
    redirect_server="$(cat <<EOF

    # Port 80 only redirects.
    server {
        listen 80 default_server;
        return 301 https://${https_authority}\$request_uri;
    }
EOF
)"
fi

NGINX_CONF=/etc/nginx/nginx.conf
if [[ $PKG == dnf ]]; then nginx_user=nginx; else nginx_user=www-data; fi
cat > "${NGINX_CONF}.new" <<EOF
# Written by the OIE installer (deploy/ec2/install.sh); re-running it rewrites this file.
user ${nginx_user};
pid /run/nginx.pid;
worker_processes auto;
error_log /var/log/nginx/error.log warn;

events { worker_connections 1024; }

http {
    server_tokens off;
    access_log /var/log/nginx/access.log;
    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_session_cache shared:SSL:10m;
${redirect_server}

    server {
        listen ${HTTPS_PORT} ssl default_server;
        ssl_certificate ${proxy_cert};
        ssl_certificate_key ${proxy_key};

        # Channel and configuration imports, message exports and deploys can be
        # large and slow; the engine has its own limits.
        client_max_body_size 0;
        proxy_request_buffering off;
        proxy_read_timeout 600s;
        proxy_send_timeout 600s;

        proxy_http_version 1.1;
        proxy_set_header Connection "";
        proxy_set_header Host \$http_host;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto https;
        # The engine scopes its session cookie to /api; widen it so the browser
        # also sends it to /apiexamples and /javadocs, which the docs check needs.
        proxy_cookie_path ~^/api\$ /;

        # A bare https://host/ goes to the web console, as on ECS.
        location = / {
            return 302 /oie-webadmin/;
        }
${docs_locations}
${login_location}

        location / {
            proxy_pass ${engine_upstream};
        }
    }
}
EOF
nginx -t -q -c "${NGINX_CONF}.new" || die "the generated nginx config does not load"
mv "${NGINX_CONF}.new" "$NGINX_CONF"
systemctl enable nginx >/dev/null
systemctl restart nginx
log "nginx: https on ${HTTPS_PORT} to the engine on 127.0.0.1:${ENGINE_PORT}; web password sign-in $([[ "$WEB_LOCAL_LOGIN" == true ]] && echo on || echo off), API docs $([[ "$API_DOCS_REQUIRE_SSO" == true ]] && echo 'SSO only' || echo public), port 80 $([[ "$HTTP_REDIRECT" == true ]] && echo redirects || echo closed)"

########################################################################
# Admin password
########################################################################
# Same job as the compose `bootstrap` service: 4.6.0 seeds admin/admin, so
# rotate it before anyone else finds the port. Idempotent. The first boot
# creates the schema on RDS, which takes a few minutes.
OIE_URL="https://127.0.0.1:${ENGINE_PORT}/api" OIE_INSECURE=true \
    OIE_ADMIN_PASSWORD="$OIE_ADMIN_PASSWORD" OIE_WAIT_TIMEOUT="${OIE_WAIT_TIMEOUT:-600}" \
    "$SCRIPTS_DIR/oie-bootstrap-admin.sh"

# Git Sync from GIT_SYNC_* (scripts/oie-gitsync-configure.sh): the ones set are
# applied on every deploy, the rest stay as the console has them. They reach it
# from SSM through this environment and are never written to oie.env.
OIE_URL="https://127.0.0.1:${ENGINE_PORT}/api" OIE_INSECURE=true OIE_PASSWORD="$OIE_ADMIN_PASSWORD" \
    "$SCRIPTS_DIR/oie-gitsync-configure.sh"

if [[ "$HTTPS_PORT" == 443 ]]; then
    log "done: https://<this host>/ (user admin)"
else
    log "done: https://<this host>:${HTTPS_PORT}/ (user admin)"
fi
