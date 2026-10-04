resource "aws_efs_file_system" "appdata" {
  encrypted = true
  tags      = merge(local.tags, { Name = "${local.name}-appdata" })

  lifecycle {
    prevent_destroy = true
  }
}

data "aws_subnet" "private" {
  for_each = toset(local.shared.private_subnet_ids)

  id = each.value
}

locals {
  # EFS allows exactly one mount target per availability zone, and the shared
  # VPC has more than one private subnet per AZ. Pick one subnet per AZ - a
  # task in any subnet of that AZ reaches the file system through its
  # AZ-local mount target. var.efs_mount_target_subnet_ids pins the choice
  # where mount targets already exist; otherwise pick the lowest subnet id
  # per AZ so it is stable.
  efs_mount_target_subnet_ids = coalescelist(var.efs_mount_target_subnet_ids, [
    for az, subnet_ids in {
      for subnet_id, subnet in data.aws_subnet.private : subnet.availability_zone => subnet_id...
    } : sort(subnet_ids)[0]
  ])
}

resource "aws_efs_mount_target" "appdata" {
  for_each        = toset(local.efs_mount_target_subnet_ids)
  file_system_id  = aws_efs_file_system.appdata.id
  subnet_id       = each.value
  security_groups = [aws_security_group.efs.id]
}

# The image runs as the unprivileged "engine" user (uid/gid 1000), but a fresh
# EFS root is owned by root, so the engine could not write appdata/. The access
# point presents its own directory, created owned by 1000 on first mount, and
# forces every file operation through it to run as 1000.
resource "aws_efs_access_point" "appdata" {
  file_system_id = aws_efs_file_system.appdata.id

  posix_user {
    uid = 1000
    gid = 1000
  }

  root_directory {
    path = "/appdata"
    creation_info {
      owner_uid   = 1000
      owner_gid   = 1000
      permissions = "0750"
    }
  }

  tags = merge(local.tags, { Name = "${local.name}-appdata" })
}

resource "aws_cloudwatch_log_group" "oie" {
  name              = "/ecs/${local.name}"
  retention_in_days = 30
  tags              = local.tags
}
