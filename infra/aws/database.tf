# Each environment gets its own database on the shared RDS instance, and
# nothing in awsshardmoduleprod creates it. The RDS instance is only reachable
# from inside the VPC, so the apply runs a one-off Fargate task there that
# creates the database (if missing) and fails the apply if it can't. The
# engine itself builds the schema inside it on first boot.
resource "aws_ecs_task_definition" "db_init" {
  family                   = "${local.name}-db-init"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = "256"
  memory                   = "512"
  execution_role_arn       = aws_iam_role.execution.arn

  container_definitions = jsonencode([{
    name = "db-init"
    # Mirrored into our ECR by the push-ecr CI job: the task has no route to
    # public registries.
    image     = "${local.shared.ecr_repository_url}:postgres-17-alpine"
    essential = true

    entryPoint = ["/bin/sh", "-c"]
    command    = [local.db_init_script]

    environment = [
      { name = "PGHOST", value = split(":", var.rds_endpoint)[0] },
      { name = "PGPORT", value = try(split(":", var.rds_endpoint)[1], "5432") },
      { name = "PGDATABASE", value = "postgres" },
      { name = "PGSSLMODE", value = "require" },
      { name = "OIE_DATABASE_NAME", value = var.rds_database_name },
    ]
    secrets = [
      { name = "PGUSER", valueFrom = "${var.app_secret_arn}:DATABASE_USERNAME::" },
      { name = "PGPASSWORD", valueFrom = "${var.app_secret_arn}:DATABASE_PASSWORD::" },
    ]

    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.oie.name
        awslogs-region        = var.region
        awslogs-stream-prefix = "db-init"
      }
    }
  }])
  tags = local.tags
}

# Runs the db-init task and waits for it during apply. The statement is
# idempotent, so re-running it (on a new endpoint, name or task revision) is
# harmless. Needs the aws CLI, which the CI apply job installs.
resource "terraform_data" "database" {
  triggers_replace = [var.rds_endpoint, var.rds_database_name, aws_ecs_task_definition.db_init.arn]

  provisioner "local-exec" {
    interpreter = ["/bin/sh", "-c"]
    environment = {
      AWS_REGION      = var.region
      CLUSTER         = local.shared.ecs_cluster_id
      TASK_DEFINITION = aws_ecs_task_definition.db_init.arn
      NETWORK_CONFIG = jsonencode({
        awsvpcConfiguration = {
          subnets        = local.shared.private_subnet_ids
          securityGroups = [aws_security_group.task.id]
          assignPublicIp = "DISABLED"
        }
      })
    }
    command = <<-EOT
      set -eu
      task_arn=$(aws ecs run-task --cluster "$CLUSTER" --task-definition "$TASK_DEFINITION" \
        --launch-type FARGATE --network-configuration "$NETWORK_CONFIG" \
        --query 'tasks[0].taskArn' --output text)
      if [ -z "$task_arn" ] || [ "$task_arn" = "None" ]; then
        echo "db-init: run-task did not start a task" >&2
        exit 1
      fi
      echo "db-init: started $task_arn, waiting for it to stop"
      aws ecs wait tasks-stopped --cluster "$CLUSTER" --tasks "$task_arn"
      exit_code=$(aws ecs describe-tasks --cluster "$CLUSTER" --tasks "$task_arn" \
        --query 'tasks[0].containers[0].exitCode' --output text)
      if [ "$exit_code" != "0" ]; then
        aws ecs describe-tasks --cluster "$CLUSTER" --tasks "$task_arn" \
          --query 'tasks[0].{stoppedReason: stoppedReason, container: containers[0].reason}' >&2
        echo "db-init: failed (exit code $exit_code) - see CloudWatch ${aws_cloudwatch_log_group.oie.name}, stream prefix db-init" >&2
        exit 1
      fi
      echo "db-init: database ready"
    EOT
  }

  # The task reaches RDS through this rule, and Fargate needs the image and
  # secret access granted to the execution role.
  depends_on = [
    aws_vpc_security_group_ingress_rule.database,
    aws_iam_role_policy_attachment.execution,
    aws_iam_role_policy.secrets,
  ]
}
