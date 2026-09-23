resource "aws_lb_target_group" "admin" {
  name        = "${local.name}-admin"
  port        = 8443
  protocol    = "HTTPS"
  target_type = "ip"
  vpc_id      = local.shared.vpc_id

  health_check {
    protocol = "HTTPS"
    path     = "/api/server/status"
    matcher  = "200"
    interval = 30
  }
  tags = local.tags
}

resource "aws_lb_listener_rule" "admin" {
  listener_arn = local.shared.alb_https_listener_arn
  priority     = var.alb_listener_rule_priority

  condition {
    host_header { values = [var.admin_host_header] }
  }
  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.admin.arn
  }
}

resource "aws_lb_target_group" "channel" {
  for_each    = local.channel_ports
  name        = "${local.name}-${each.value}"
  port        = tonumber(each.value)
  protocol    = "TCP"
  target_type = "ip"
  vpc_id      = local.shared.vpc_id

  preserve_client_ip = false

  health_check {
    protocol = "TCP"
    port     = "traffic-port"
  }
  tags = local.tags
}
