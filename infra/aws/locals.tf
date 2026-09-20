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
}