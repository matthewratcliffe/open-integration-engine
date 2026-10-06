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
  # Each deployment (environment/instance) reserves its own block of 100 NLB
  # ports for channel traffic, so no two engines' channels collide on the
  # shared NLB; the plan job fails if another app has a listener in the block.
  # A new instance needs its own entry here. Only the first
  # var.channel_port_count ports are open - an NLB listener serves one port,
  # so each open port has its own listener and target group.
  channel_port_ranges = {
    "staging/oie"     = { min = 50000, max = 50099 }
    "production/oie1" = { min = 50500, max = 50599 }
  }
  channel_port_range = local.channel_port_ranges[local.deployment]

  # for_each only accepts maps or sets of strings - stringify once for reuse.
  channel_port_numbers = range(local.channel_port_range.min, local.channel_port_range.min + var.channel_port_count)
  channel_ports        = toset([for port in local.channel_port_numbers : tostring(port)])

  # A bare address means that host.
  trusted_cidrs = [for cidr in var.trusted_cidrs : strcontains(cidr, "/") ? cidr : "${cidr}/32"]

  # Allowed sources per open port: public (0.0.0.0/0) unless
  # var.channel_port_sources restricts it, and then that source plus the
  # trusted ranges, which reach every port. Joined into a string so
  # neighbouring ports' source lists compare simply.
  channel_port_source = {
    for port in local.channel_ports : port => (
      !contains(keys(var.channel_port_sources), port) ? "0.0.0.0/0" : join(",", sort(distinct(concat(
        local.trusted_cidrs,
        [strcontains(var.channel_port_sources[port], "/") ? var.channel_port_sources[port] : "${var.channel_port_sources[port]}/32"]
      ))))
    )
  }

  # The NLB security group is shared with other apps and capped on rules, so
  # consecutive open ports with the same sources share rules: by default all
  # of them are one public rule. A run starts where the sources change and
  # ends before the next change.
  channel_ingress_runs = {
    for start in local.channel_port_numbers : "${start}" => {
      from = start
      to = [
        for port in local.channel_port_numbers : port
        if port >= start && lookup(local.channel_port_source, "${port + 1}", "") != local.channel_port_source["${port}"]
      ][0]
      cidrs = split(",", local.channel_port_source["${start}"])
    }
    if lookup(local.channel_port_source, "${start - 1}", "") != local.channel_port_source["${start}"]
  }
  # One NLB rule per run and source.
  channel_ingress_rules = merge([
    for start, run in local.channel_ingress_runs : {
      for cidr in run.cidrs : "${start} ${cidr}" => { from = run.from, to = run.to, cidr = cidr }
    }
  ]...)

  # What reaches the task directly (not through a load balancer): the trusted
  # ranges and security group, on every port it exposes.
  task_exposed_ports = {
    admin   = { from = 8443, to = 8443 }
    channel = { from = local.channel_port_range.min, to = local.channel_port_range.max }
  }
  task_trusted_cidr_rules = merge([
    for name, ports in local.task_exposed_ports : {
      for cidr in local.trusted_cidrs : "${name} ${cidr}" => merge(ports, { cidr = cidr })
    }
  ]...)

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