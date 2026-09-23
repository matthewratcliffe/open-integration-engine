resource "aws_ecs_task_definition" "oie" {
  family                   = local.name
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.task_cpu
  memory                   = var.task_memory
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.task.arn

  volume {
    name = "appdata"
    efs_volume_configuration {
      file_system_id     = aws_efs_file_system.appdata.id
      transit_encryption = "ENABLED"
    }
  }

  container_definitions = jsonencode([{
    name      = "oie"
    image     = "${local.shared.ecr_repository_url}:${var.image_tag}"
    essential = true

    entryPoint = ["/bin/bash", "-lc"]
    command = [
      "if [[ -n \"$KEYSTORE_BASE64\" && ! -s /opt/engine/appdata/keystore.jks ]]; then printf '%s' \"$KEYSTORE_BASE64\" | base64 -d > /opt/engine/appdata/keystore.jks && chmod 600 /opt/engine/appdata/keystore.jks; fi; exec /usr/local/bin/oie-entrypoint ./oieserver"
    ]

    portMappings = concat(
      [{ containerPort = 8443, protocol = "tcp" }],
      [for port in var.channel_ports : { containerPort = port, protocol = "tcp" }]
    )

    environment = [
      { name = "DATABASE", value = "postgres" },
      { name = "DATABASE_URL", value = "jdbc:postgresql://${var.rds_endpoint}/${var.rds_database_name}" },
      { name = "OIE_HEAP_MAX", value = "2g" },
      { name = "OIE_CLUSTER_ENABLED", value = "false" },
      { name = "SERVER_STARTUP_DEPLOY", value = "true" },
    ]

    secrets = [for key in local.app_secret_keys : {
      name      = key
      valueFrom = "${var.app_secret_arn}:${key}::"
    }]
    mountPoints = [{
      sourceVolume  = "appdata"
      containerPath = "/opt/engine/appdata"
      readOnly      = false
    }]

    healthCheck = {
      command     = ["CMD-SHELL", "curl -fsSk -H 'X-Requested-With: healthcheck' https://127.0.0.1:8443/api/server/status || exit 1"]
      interval    = 30
      timeout     = 5
      retries     = 5
      startPeriod = 120
    }

    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.oie.name
        awslogs-region        = var.region
        awslogs-stream-prefix = "ecs"
      }
    }
  }])
  tags = local.tags
}

resource "aws_ecs_service" "oie" {
  name                   = local.name
  cluster                = local.shared.ecs_cluster_id
  task_definition        = aws_ecs_task_definition.oie.arn
  desired_count          = 1
  launch_type            = "FARGATE"
  enable_execute_command = true

  deployment_minimum_healthy_percent = 0
  deployment_maximum_percent         = 100
  health_check_grace_period_seconds  = 180

  network_configuration {
    subnets          = local.shared.private_subnet_ids
    security_groups  = [aws_security_group.task.id]
    assign_public_ip = false
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.admin.arn
    container_name   = "oie"
    container_port   = 8443
  }

  dynamic "load_balancer" {
    for_each = local.channel_ports
    content {
      target_group_arn = aws_lb_target_group.channel[load_balancer.value].arn
      container_name   = "oie"
      container_port   = tonumber(load_balancer.value)
    }
  }

  depends_on = [aws_lb_listener_rule.admin, aws_lb_listener.channel, aws_efs_mount_target.appdata]
  tags       = local.tags
}
