resource "aws_security_group" "task" {
  name   = "${local.name}-task"
  vpc_id = local.shared.vpc_id
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
  tags = local.tags
}
resource "aws_vpc_security_group_ingress_rule" "admin" {
  security_group_id            = aws_security_group.task.id
  referenced_security_group_id = local.shared.alb_security_group_id
  from_port                    = 8443
  to_port                      = 8443
  ip_protocol                  = "tcp"
}
resource "aws_vpc_security_group_ingress_rule" "channel" {
  # Open once for the whole reserved range rather than per configured
  # channel port, so adding/removing a channel never requires a
  # security-group change.
  security_group_id            = aws_security_group.task.id
  referenced_security_group_id = var.nlb_security_group_id
  from_port                    = local.channel_port_range.min
  to_port                      = local.channel_port_range.max
  ip_protocol                  = "tcp"
}
resource "aws_vpc_security_group_ingress_rule" "nlb_channel" {
  # Owned directly by this project rather than centralized in
  # awsshardmoduleprod - each app on the shared NLB manages its own rules as
  # standalone resources, so no two apps' Terraform configs fight over the
  # same security group. The client's source is filtered here, at the NLB:
  # the task only ever sees the NLB's addresses (preserve_client_ip = false).
  # One rule per source of each run of consecutive open ports sharing sources
  # (locals.tf).
  for_each          = local.channel_ingress_rules
  security_group_id = var.nlb_security_group_id
  cidr_ipv4         = each.value.cidr
  from_port         = each.value.from
  to_port           = each.value.to
  ip_protocol       = "tcp"
  description       = "OIE (${local.deployment}) channel traffic"
}
resource "aws_vpc_security_group_ingress_rule" "database" {
  security_group_id            = var.rds_security_group_id
  referenced_security_group_id = aws_security_group.task.id
  from_port                    = 5432
  to_port                      = 5432
  ip_protocol                  = "tcp"
  description                  = "PostgreSQL from OIE ECS task"
}
resource "aws_security_group" "efs" {
  name   = "${local.name}-efs"
  vpc_id = local.shared.vpc_id
  ingress {
    from_port       = 2049
    to_port         = 2049
    protocol        = "tcp"
    security_groups = [aws_security_group.task.id]
  }
  tags = local.tags
}