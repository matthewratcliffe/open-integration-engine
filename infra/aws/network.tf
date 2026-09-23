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