resource "aws_efs_file_system" "appdata" {
  encrypted = true
  tags      = merge(local.tags, { Name = "${local.name}-appdata" })

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_efs_mount_target" "appdata" {
  for_each        = toset(local.shared.private_subnet_ids)
  file_system_id  = aws_efs_file_system.appdata.id
  subnet_id       = each.value
  security_groups = [aws_security_group.efs.id]
}

resource "aws_cloudwatch_log_group" "oie" {
  name              = "/ecs/${local.name}"
  retention_in_days = 30
  tags              = local.tags
}
