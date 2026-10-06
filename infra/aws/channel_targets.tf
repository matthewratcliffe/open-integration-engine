# Registers the running task in the channel target groups. The ECS service
# can't: it attaches at most 5 target groups, and each open channel port has
# its own. lambda/channel_targets.py reconciles every channel group to the
# service's running task IPs - on each task state change, and every 5 minutes
# to heal a missed event or fill a target group a later apply added. The
# service runs one task at a time (deployment_maximum_percent = 100), so
# whatever the groups hold beyond the running task is stale.

data "aws_caller_identity" "current" {}

# Under .terraform so the CI plan job's artifacts carry it to the apply job: a
# saved plan reads the zip at apply time and doesn't rebuild it.
data "archive_file" "channel_targets" {
  type        = "zip"
  source_file = "${path.module}/lambda/channel_targets.py"
  output_path = "${path.module}/.terraform/build/channel_targets.zip"
}

resource "aws_iam_role" "channel_targets" {
  name = "${local.name}-channel-targets"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Action    = "sts:AssumeRole"
      Principal = { Service = "lambda.amazonaws.com" }
    }]
  })
  tags = local.tags
}

resource "aws_iam_role_policy_attachment" "channel_targets_logs" {
  role       = aws_iam_role.channel_targets.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"
}

resource "aws_iam_role_policy" "channel_targets" {
  name = "channel-targets"
  role = aws_iam_role.channel_targets.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = [
          "ecs:ListTasks",
          "ecs:DescribeTasks",
          "elasticloadbalancing:DescribeTargetGroups",
          "elasticloadbalancing:DescribeTargetHealth",
        ]
        Resource = "*"
      },
      {
        Effect   = "Allow"
        Action   = ["elasticloadbalancing:RegisterTargets", "elasticloadbalancing:DeregisterTargets"]
        Resource = "arn:aws:elasticloadbalancing:${var.region}:${data.aws_caller_identity.current.account_id}:targetgroup/${local.name}-*/*"
      },
    ]
  })
}

resource "aws_cloudwatch_log_group" "channel_targets" {
  name              = "/aws/lambda/${local.name}-channel-targets"
  retention_in_days = 30
  tags              = local.tags
}

resource "aws_lambda_function" "channel_targets" {
  function_name    = "${local.name}-channel-targets"
  role             = aws_iam_role.channel_targets.arn
  runtime          = "python3.13"
  handler          = "channel_targets.handler"
  filename         = data.archive_file.channel_targets.output_path
  source_code_hash = data.archive_file.channel_targets.output_base64sha256
  timeout          = 60

  environment {
    variables = {
      CLUSTER             = local.shared.ecs_cluster_id
      SERVICE             = local.name
      TARGET_GROUP_PREFIX = "${local.name}-"
    }
  }

  depends_on = [aws_cloudwatch_log_group.channel_targets, aws_iam_role_policy_attachment.channel_targets_logs]
  tags       = local.tags
}

resource "aws_cloudwatch_event_rule" "channel_targets_task_state" {
  name        = "${local.name}-channel-targets-task-state"
  description = "OIE (${local.deployment}) task state changes - re-registers the channel targets"
  event_pattern = jsonencode({
    source        = ["aws.ecs"]
    "detail-type" = ["ECS Task State Change"]
    detail        = { group = ["service:${local.name}"] }
  })
  tags = local.tags
}

resource "aws_cloudwatch_event_rule" "channel_targets_schedule" {
  name                = "${local.name}-channel-targets-schedule"
  description         = "OIE (${local.deployment}) periodic channel target reconcile"
  schedule_expression = "rate(5 minutes)"
  tags                = local.tags
}

resource "aws_cloudwatch_event_target" "channel_targets_task_state" {
  rule = aws_cloudwatch_event_rule.channel_targets_task_state.name
  arn  = aws_lambda_function.channel_targets.arn
}

resource "aws_cloudwatch_event_target" "channel_targets_schedule" {
  rule = aws_cloudwatch_event_rule.channel_targets_schedule.name
  arn  = aws_lambda_function.channel_targets.arn
}

resource "aws_lambda_permission" "channel_targets" {
  for_each = {
    task-state = aws_cloudwatch_event_rule.channel_targets_task_state.arn
    schedule   = aws_cloudwatch_event_rule.channel_targets_schedule.arn
  }
  statement_id  = "events-${each.key}"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.channel_targets.function_name
  principal     = "events.amazonaws.com"
  source_arn    = each.value
}
