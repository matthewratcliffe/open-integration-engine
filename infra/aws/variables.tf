variable "region" { type = string }
variable "environment" { type = string }
variable "shared_outputs_path" { type = string }
variable "image_tag" { type = string }
variable "admin_host_header" { type = string }
variable "alb_listener_rule_priority" { type = number }
variable "app_secret_arn" { type = string }
variable "rds_endpoint" { type = string }
variable "rds_database_name" { type = string }
variable "rds_secret_arn" { type = string }
variable "rds_security_group_id" { type = string }

variable "channel_ports" {
  type    = set(number)
  default = [8081, 6661]
}

variable "channel_ingress_cidrs" {
  description = "Existing NLB subnet CIDRs; target groups disable client IP preservation."
  type        = set(string)
}

variable "task_cpu" {
  type    = string
  default = "2048"
}

variable "task_memory" {
  type    = string
  default = "4096"
}
