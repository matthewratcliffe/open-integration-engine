locals {
  shared = jsondecode(file(var.shared_outputs_path))
  name   = "au-${var.environment}-oie"
  tags = {
    Application = "oie"
    Environment = var.environment
  }
  app_secret_keys = [
    "OIE_ADMIN_PASSWORD",
    "KEYSTORE_PASSWORD",
    "KEYSTORE_BASE64",
  ]
}