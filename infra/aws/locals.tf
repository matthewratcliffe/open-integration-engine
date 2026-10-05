locals {
  shared_raw = jsondecode(file(var.shared_outputs_path))
  shared     = { for key, value in local.shared_raw : key => try(value.value, value) }
  # One engine per environment + instance. Staging is the single instance
  # "oie" (au-staging-oie, unchanged); production instances are oie1, oie2, ...
  name       = "au-${var.environment}-${var.instance}"
  deployment = "${var.environment}/${var.instance}"
  tags = {
    Application = "oie"
    Environment = var.environment
  }
  app_secret_keys = [
    "DATABASE_USERNAME",
    "DATABASE_PASSWORD",
    "OIE_ADMIN_PASSWORD",
    "KEYSTORE_PASSWORD",
    "KEYSTORE_BASE64",
  ]
  # for_each only accepts maps or sets of strings, not the set(number) that
  # var.channel_ports is declared as - stringify it once for reuse.
  channel_ports = toset([for port in var.channel_ports : tostring(port)])

  # Each deployment (environment/instance) gets its own reserved block of NLB
  # ports for channel traffic, so no two engines' channels can collide on the
  # shared NLB. A new instance needs its own entry here. This only reserves the range (the security-group ingress
  # rule opens it in full) - no channels are deployed by default, and
  # individual channel ports (var.channel_ports) are only allowed, and only
  # get their own NLB listener/target group, once actually added.
  channel_port_ranges = {
    "staging/oie"     = { min = 50000, max = 50100 }
    "production/oie1" = { min = 50500, max = 50600 }
  }
  channel_port_range = local.channel_port_ranges[local.deployment]

  # The engine container's start command. appdata (EFS) persists across tasks,
  # and after first boot the keystore there also holds the engine's
  # data-encryption key, so KEYSTORE_BASE64 is only installed when there is no
  # keystore yet. A new KEYSTORE_RESET value moves the existing one aside first
  # (kept, not deleted); appdata/.keystore-reset records which value was applied
  # so a reset happens once, not on every restart.
  engine_start_script = <<-EOT
    set -eu
    appdata=/opt/engine/appdata
    if [[ -n "$${KEYSTORE_RESET:-}" && "$(cat "$appdata/.keystore-reset" 2>/dev/null || true)" != "$KEYSTORE_RESET" ]]; then
      if [[ -e "$appdata/keystore.jks" ]]; then
        mv "$appdata/keystore.jks" "$appdata/keystore.jks.replaced-$KEYSTORE_RESET"
        echo "[keystore] reset $KEYSTORE_RESET: moved keystore.jks to keystore.jks.replaced-$KEYSTORE_RESET"
      fi
      printf '%s' "$KEYSTORE_RESET" > "$appdata/.keystore-reset"
    fi
    if [[ -n "$${KEYSTORE_BASE64:-}" && ! -s "$appdata/keystore.jks" ]]; then
      printf '%s' "$KEYSTORE_BASE64" | base64 -d > "$appdata/keystore.jks"
      chmod 600 "$appdata/keystore.jks"
      echo "[keystore] installed the supplied keystore"
    fi
    exec /usr/local/bin/oie-entrypoint ./oieserver
  EOT

  # Run by the db-init task (database.tf). The statement goes through stdin
  # rather than -c so psql substitutes :'db' (a quoted literal) and %I quotes
  # the name as an identifier - environment database names contain hyphens.
  db_init_script = <<-EOT
    set -eu
    echo "[db-init] ensuring database $OIE_DATABASE_NAME exists on $PGHOST"
    printf '%s\n' "SELECT format('CREATE DATABASE %I', :'db') WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = :'db')\gexec" \
      | psql -v ON_ERROR_STOP=1 -v db="$OIE_DATABASE_NAME"
    # Read it back so the log proves the database is there, not just that psql
    # exited cleanly.
    found=$(printf '%s\n' "SELECT datname FROM pg_database WHERE datname = :'db'" \
      | psql -v ON_ERROR_STOP=1 -tA -v db="$OIE_DATABASE_NAME")
    if [ "$found" != "$OIE_DATABASE_NAME" ]; then
      echo "[db-init] database $OIE_DATABASE_NAME not found on $PGHOST after create" >&2
      exit 1
    fi
    echo "[db-init] database $OIE_DATABASE_NAME present on $PGHOST"
  EOT
}