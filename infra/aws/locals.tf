locals {
  shared_raw = jsondecode(file(var.shared_outputs_path))
  shared     = { for key, value in local.shared_raw : key => try(value.value, value) }
  name       = "au-${var.environment}-oie"
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

  # Each environment gets its own reserved block of NLB ports for channel
  # traffic, so staging and production channels can never collide on the
  # shared NLB. This only reserves the range (the security-group ingress
  # rule opens it in full) - no channels are deployed by default, and
  # individual channel ports (var.channel_ports) are only allowed, and only
  # get their own NLB listener/target group, once actually added.
  channel_port_ranges = {
    staging    = { min = 50000, max = 50100 }
    production = { min = 50500, max = 50600 }
  }
  channel_port_range = local.channel_port_ranges[var.environment]

  # Run by the db-init task (database.tf). The statement goes through stdin
  # rather than -c so psql substitutes :'db' (a quoted literal) and %I quotes
  # the name as an identifier - environment database names contain hyphens.
  db_init_script = <<-EOT
    set -eu
    echo "[db-init] ensuring database $OIE_DATABASE_NAME exists on $PGHOST"
    printf '%s\n' "SELECT format('CREATE DATABASE %I', :'db') WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = :'db')\gexec" \
      | psql -v ON_ERROR_STOP=1 -v db="$OIE_DATABASE_NAME"
    echo "[db-init] done"
  EOT
}