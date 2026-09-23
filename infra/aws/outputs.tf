output "admin_url" { value = "https://${var.admin_host_header}" }
output "ecs_service_name" { value = aws_ecs_service.oie.name }
output "ecs_cluster_name" { value = local.shared.ecs_cluster_name }
