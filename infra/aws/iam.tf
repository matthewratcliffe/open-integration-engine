data "aws_iam_policy_document" "task_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "execution" {
  name               = "${local.name}-execution"
  assume_role_policy = data.aws_iam_policy_document.task_assume.json
  tags               = local.tags
}

# ECS uses the execution role, not the task role, to pull the images and to ship the engine's and
# the database initialiser's stdout and stderr through the awslogs driver. This policy replaces AWS's
# managed AmazonECSTaskExecutionRolePolicy, which allows both on every repository and log group in
# the account, with the shared image repository and this instance's log group (INFRA-1381).
resource "aws_iam_role_policy" "execution" {
  name = "ecs-execution"
  role = aws_iam_role.execution.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        # GetAuthorizationToken does not support resource-level permissions.
        Effect   = "Allow"
        Action   = ["ecr:GetAuthorizationToken"]
        Resource = "*"
      },
      {
        Effect   = "Allow"
        Action   = ["ecr:BatchCheckLayerAvailability", "ecr:BatchGetImage", "ecr:GetDownloadUrlForLayer"]
        Resource = local.shared.ecr_repository_arn
      },
      {
        # ":*" matches the log streams inside the group.
        Effect   = "Allow"
        Action   = ["logs:CreateLogStream", "logs:PutLogEvents"]
        Resource = "${aws_cloudwatch_log_group.oie.arn}:*"
      }
    ]
  })
}

resource "aws_iam_role_policy" "secrets" {
  name = "secrets"
  role = aws_iam_role.execution.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["secretsmanager:GetSecretValue"]
      Resource = var.app_secret_arn
    }]
  })
}

resource "aws_iam_role" "task" {
  name               = "${local.name}-task"
  assume_role_policy = data.aws_iam_policy_document.task_assume.json
  tags               = local.tags
}
