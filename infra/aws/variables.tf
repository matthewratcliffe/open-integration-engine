variable "region" { type = string }
variable "environment" { type = string }

variable "instance" {
  description = "Which engine within the environment: staging runs the single instance \"oie\", production runs oie1, oie2, ... Each instance is a separate engine with its own service, EFS, database, keystore, state and NLB port range."
  type        = string
  default     = "oie"

  validation {
    condition     = can(regex("^[a-z][a-z0-9]{1,9}$", var.instance))
    error_message = "instance must be 2-10 lowercase letters/digits, starting with a letter (it goes into resource names with length limits)."
  }
}
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

variable "channel_port_count" {
  description = "How many channel ports to open on the shared NLB: the first N of this deployment's reserved range (locals.channel_port_ranges), each with its own listener and target group. Raise it to expand; the rest of the range stays reserved but closed."
  type        = number
  default     = 4

  validation {
    condition     = var.channel_port_count >= 0 && floor(var.channel_port_count) == var.channel_port_count
    error_message = "channel_port_count must be a whole number, 0 or more."
  }
  validation {
    condition     = var.channel_port_count <= local.channel_port_range.max - local.channel_port_range.min + 1
    error_message = "channel_port_count is larger than this deployment's reserved NLB port range (locals.channel_port_ranges)."
  }
}

variable "channel_port_sources" {
  description = "Restricts an open channel port to one source, as port => IPv4 address or CIDR (e.g. { \"50003\" = \"203.0.113.7\" }). A bare address means that host (/32). A restricted port also allows trusted_cidrs; ports not listed are public (0.0.0.0/0)."
  type        = map(string)
  default     = {}

  validation {
    condition = alltrue([
      for cidr in values(var.channel_port_sources) :
      can(cidrnetmask(strcontains(cidr, "/") ? cidr : "${cidr}/32")) && cidrsubnet(strcontains(cidr, "/") ? cidr : "${cidr}/32", 0, 0) == (strcontains(cidr, "/") ? cidr : "${cidr}/32")
    ])
    error_message = "Each channel port source must be an IPv4 address or a CIDR with no host bits set (10.1.0.0/16, not 10.1.2.3/16)."
  }
  validation {
    condition     = alltrue([for port in keys(var.channel_port_sources) : contains(local.channel_ports, port)])
    error_message = "channel_port_sources names a port that isn't open - only the first channel_port_count ports of this deployment's range are."
  }
}

variable "trusted_cidrs" {
  description = "Trusted IPv4 addresses or CIDRs (a bare address means that host). When set, only they reach the admin console through the shared ALB (443) - empty leaves it public - and they are also allowed on any channel port that channel_port_sources restricts. The task itself only accepts connections from the ALB and NLB."
  type        = list(string)
  default     = []

  validation {
    condition = alltrue([
      for cidr in var.trusted_cidrs :
      can(cidrnetmask(strcontains(cidr, "/") ? cidr : "${cidr}/32")) && cidrsubnet(strcontains(cidr, "/") ? cidr : "${cidr}/32", 0, 0) == (strcontains(cidr, "/") ? cidr : "${cidr}/32")
    ])
    error_message = "Each trusted CIDR must be an IPv4 address or a CIDR with no host bits set (10.1.0.0/16, not 10.1.2.3/16)."
  }
  # An ALB rule takes at most 5 condition values in total, and the / redirect
  # rule already uses two (host and path) - so at most 3 source CIDRs.
  validation {
    condition     = length(var.trusted_cidrs) <= 3
    error_message = "trusted_cidrs (CI: TRUSTED_CIDRS) takes at most 3 entries - an ALB rule can't match more than 3 source CIDRs alongside its host and path."
  }
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
    "sha256:69a6b2285000d6c7356aaa547746528fe0bf145ba9bdf5d595fb9a390aff54ff@https://github.com/gibson9583/oie-web-support-plugin/releases/download/v1.3.0/websupport-1.3.0.zip",
    "sha256:4ac72f14650be5fcf2d1ea91b4abaa5ea81344982a5e0036bb1e168e71152480@https://github.com/gibson9583/oie-sentinel/releases/download/v1.2.2/sentinel-1.2.2.zip",
    "sha256:2e8b446e765c3b90157aac0d560389309c8d2c37ca2bf9615e600d5b5e8816eb@https://github.com/gibson9583/engine-thread-viewer/releases/download/v1.0.8/thread-viewer-1.0.8.zip",
    "sha256:38e9bc9a350061e7492952cc03e3a0bff6b5d55d022b9714aa538add01b7128a@https://github.com/matthewratcliffe/oie-tls-manager/releases/download/1.1.11/tls-manager-1.1.11.zip",
    "sha256:92efb860967065f2b2d7ed89ccc014c9b9e4309d6d6e7e1d5f2369a295b0eb44@https://github.com/gibson9583/oie-oidc-auth/releases/download/v1.0.3/oidcauth-1.0.3.zip",
  ]
}

variable "oidc_settings" {
  description = "Single sign-on settings pinned from GitLab (OIDC_* CI variables, see docs/sso.md), as OIE_OIDC_* environment variable name => value. Each one overrides the console's stored policy and shows read-only there. Empty (the default) passes none and leaves SSO to the console. The client secret is not here - see oidc_client_secret_set."
  type        = map(string)
  default     = {}

  validation {
    condition     = alltrue([for name in keys(var.oidc_settings) : can(regex("^OIE_OIDC_[A-Z0-9_]+$", name)) && name != "OIE_OIDC_CLIENT_SECRET"])
    error_message = "oidc_settings keys must be OIE_OIDC_* variable names, and the client secret goes through the app secret (oidc_client_secret_set), not here."
  }
}

variable "oidc_client_secret_set" {
  description = "Whether the app secret carries OIE_OIDC_CLIENT_SECRET (CI adds it when OIDC_CLIENT_SECRET is set). Only then is it mapped into the task - mapping a key the secret lacks stops the task from starting."
  type        = bool
  default     = false
}
