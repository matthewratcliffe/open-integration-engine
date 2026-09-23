variable "region" { type = string }
variable "environment" { type = string }
variable "shared_outputs_path" { type = string }
variable "image_tag" { type = string }
variable "admin_host_header" { type = string }
variable "alb_listener_rule_priority" { type = number }
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
