output "admin_url" { value = "https://${var.admin_host_header}" }
output "ecs_service_name" { value = aws_ecs_service.oie.name }
output "ecs_cluster_name" { value = local.shared.ecs_cluster_name }

output "existing_nlb_listener_requirements" {
  description = "Manually configure these TCP listeners on the existing NLB."
  value = {
    for port, target_group in aws_lb_target_group.channel : tostring(port) => {
      listener_protocol = "TCP"
      listener_port     = port
      target_group_arn  = target_group.arn
      target_type       = "ip"
      health_check      = "TCP:${port}"
    }
  }
}
