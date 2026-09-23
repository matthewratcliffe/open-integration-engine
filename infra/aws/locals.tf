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
}