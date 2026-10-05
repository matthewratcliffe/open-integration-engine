resource "aws_lb_target_group" "admin" {
  name        = "${local.name}-admin"
  port        = 8443
  protocol    = "HTTPS"
  target_type = "ip"
  vpc_id      = local.shared.vpc_id

  # Not /api/server/status: the API rejects requests without an
  # X-Requested-With header (400), and ALB health checks can't send one, so the
  # target was marked unhealthy and ECS replaced the task every few minutes.
  # The landing page needs no header. The container health check (ecs.tf)
  # still probes the API with the header.
  health_check {
    protocol = "HTTPS"
    path     = "/"
    matcher  = "200-399"
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

# A bare https://host/ goes straight to the web console, as the k8s ingress's
# app-root does. Only the exact path "/" matches; /api/, /webstart.jnlp and the
# rest fall through to the forward rule above, so this needs the lower
# (earlier-evaluated) priority. 302, not 301, so browsers don't cache it.
resource "aws_lb_listener_rule" "admin_root_redirect" {
  listener_arn = local.shared.alb_https_listener_arn
  priority     = var.alb_redirect_rule_priority

  condition {
    host_header { values = [var.admin_host_header] }
  }
  condition {
    path_pattern { values = ["/"] }
  }
  action {
    type = "redirect"
    redirect {
      path        = "/oie-webadmin/"
      status_code = "HTTP_302"
    }
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

  lifecycle {
    precondition {
      condition     = tonumber(each.value) >= local.channel_port_range.min && tonumber(each.value) <= local.channel_port_range.max
      error_message = "Channel port ${each.value} is outside this environment's reserved NLB port range (${local.channel_port_range.min}-${local.channel_port_range.max})."
    }
  }
}

# Each channel port gets its own listener on the shared NLB. Terraform owns
# these directly rather than requiring a manual post-apply step, since the
# ECS service (below) needs each target group to already have an associated
# load balancer at creation time -- a manual step can't satisfy that on a
# brand-new service.
resource "aws_lb_listener" "channel" {
  for_each          = local.channel_ports
  load_balancer_arn = var.nlb_arn
  port              = tonumber(each.value)
  protocol          = "TCP"

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.channel[each.key].arn
  }

  tags = local.tags
}
