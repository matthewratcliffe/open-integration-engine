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
      authorization_config {
        access_point_id = aws_efs_access_point.appdata.id
      }
    }
  }

  container_definitions = jsonencode([{
    name      = "oie"
    image     = "${local.shared.ecr_repository_url}:${var.image_tag}"
    essential = true

    entryPoint = ["/bin/bash", "-lc"]
    command    = [local.engine_start_script]

    portMappings = concat(
      [{ containerPort = 8443, protocol = "tcp" }],
      [for port in local.channel_port_numbers : { containerPort = port, protocol = "tcp" }]
    )

    environment = [
      { name = "DATABASE", value = "postgres" },
      { name = "DATABASE_URL", value = "jdbc:postgresql://${var.rds_endpoint}/${var.rds_database_name}" },
      { name = "OIE_HEAP_MAX", value = "2g" },
      { name = "OIE_CLUSTER_ENABLED", value = "false" },
      { name = "SERVER_STARTUP_DEPLOY", value = "true" },
      { name = "KEYSTORE_RESET", value = var.keystore_reset },
      # Downloaded from GitHub through the VPC's NAT gateway, and cached on EFS
      # (appdata/extension-cache) so a restart survives a failed download.
      { name = "OIE_EXTENSION_URLS", value = join(",", var.extension_urls) },
    ]

    # The entrypoint reads the keystore password from KEYSTORE_STOREPASS and
    # KEYSTORE_KEYPASS (keystore.storepass/keypass), not KEYSTORE_PASSWORD -
    # map the one secret onto both, as compose and k8s do.
    secrets = concat(
      [for key in local.app_secret_keys : {
        name      = key
        valueFrom = "${var.app_secret_arn}:${key}::"
      }],
      [for name in ["KEYSTORE_STOREPASS", "KEYSTORE_KEYPASS"] : {
        name      = name
        valueFrom = "${var.app_secret_arn}:KEYSTORE_PASSWORD::"
      }]
    )
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

  # The channel target groups aren't attached here: a service takes at most 5
  # target groups. channel_targets.tf registers the task in them instead, and
  # its state-change rule must exist before the first task starts.
  depends_on = [aws_lb_listener_rule.admin, aws_cloudwatch_event_target.channel_targets_task_state, aws_efs_mount_target.appdata, terraform_data.database]
  tags       = local.tags
}
