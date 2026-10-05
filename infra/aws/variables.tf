variable "region" { type = string }
variable "environment" { type = string }
variable "shared_outputs_path" { type = string }
variable "image_tag" { type = string }
variable "admin_host_header" { type = string }
variable "alb_listener_rule_priority" { type = number }

variable "alb_redirect_rule_priority" {
  description = "Priority of the / -> /oie-webadmin/ redirect on the shared ALB listener. Must be lower than alb_listener_rule_priority (it is evaluated first) and unused by any other app's rule."
  type        = number

  validation {
    condition     = var.alb_redirect_rule_priority < var.alb_listener_rule_priority
    error_message = "alb_redirect_rule_priority must be lower than alb_listener_rule_priority, or the forward rule matches / first."
  }
}
variable "app_secret_arn" { type = string }
variable "rds_endpoint" { type = string }
variable "rds_database_name" { type = string }
variable "rds_security_group_id" { type = string }

variable "channel_ports" {
  description = "Ports for channels to expose via the NLB. Empty by default - no channels are deployed until you add some. Each port must fall within this environment's reserved NLB port range (staging 50000-50100, production 50500-50600 - see locals.channel_port_ranges)."
  type        = set(number)
  default     = []
}

variable "nlb_security_group_id" { type = string }
variable "nlb_arn" { type = string }

variable "efs_mount_target_subnet_ids" {
  description = "Private subnets to place EFS mount targets in - at most one per availability zone. Leave empty to derive one subnet per AZ automatically; pin it when mount targets already exist, so their subnets are preserved instead of replaced."
  type        = list(string)
  default     = []
}

variable "task_cpu" {
  type    = string
  default = "2048"
}

variable "task_memory" {
  type    = string
  default = "4096"
}

variable "keystore_reset" {
  description = "Set to a new value (e.g. a date) to replace this environment's keystore: on its next start the engine moves appdata/keystore.jks aside to keystore.jks.replaced-<value> and installs KEYSTORE_BASE64, or generates a new one. Anything encrypted under the old keystore stays unreadable until it is moved back. Empty (the default) never resets."
  type        = string
  default     = ""
}

variable "extension_urls" {
  description = "Community extensions installed at engine start (OIE_EXTENSION_URLS), each pinned as sha256:<hex>@<url> - they run inside the engine's JVM. The same list as .env and deploy/k8s/config.yaml; keep them in step when bumping a version. Web Support serves the console at /oie-webadmin/, which is a 404 without it. CI replaces the whole list when OIE_EXTENSION_URLS is set."
  type        = list(string)
  default = [
    "sha256:11014e5fc2a2b9ca5ef5c6750c42fc65ce832b3f7e918e076f5e1f9fa71fbf9a@https://github.com/gibson9583/oie-web-support-plugin/releases/download/v1.0.3/websupport-1.0.3.zip",
    "sha256:c878511360f7efbd1db3757c8a8f46438dff03e4ca700d5f049fb21dc65c90e9@https://github.com/gibson9583/oie-sentinel/releases/download/v1.1.0/sentinel-1.1.0.zip",
    "sha256:7597579a6cbe54070ba9b64c1ee3caaef6433e04997cc16cd4d2c13ebc9201da@https://github.com/gibson9583/engine-thread-viewer/releases/download/v1.0.6/thread-viewer-1.0.6.zip",
    "sha256:a78a0db772de712f99b5842968ebc341f739d00b48c18be2f4cfa923283ed78c@https://github.com/NovaMap-Health/tls-manager-plugin/releases/download/1.0.8/tls-manager-1.0.8.zip",
    "sha256:a18d208ca4e6ca0700790d971fde5b4212df70b3dc5b34ae3cc9032afdb3a5c2@https://github.com/gibson9583/oie-oidc-auth/releases/download/v1.0.1/oidcauth-1.0.1.zip",
  ]
}
