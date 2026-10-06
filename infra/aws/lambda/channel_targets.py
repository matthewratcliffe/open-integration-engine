"""Points this engine's channel target groups at its running task.

An ECS service attaches at most 5 target groups, so the channel ports' target
groups (one per NLB port) are kept in step here instead. Every invocation
reconciles from scratch rather than acting on the event: each channel target
group ends up holding exactly the IPs of the service's running tasks, on that
group's port. That makes a missed or repeated event harmless, and a target
group added by a later apply is filled on the next run.
"""

import os
import re

import boto3

ecs = boto3.client("ecs")
elbv2 = boto3.client("elbv2")

CLUSTER = os.environ["CLUSTER"]
SERVICE = os.environ["SERVICE"]
# The channel groups are "<name>-<port>"; the admin group, "<name>-admin",
# is the service's own and is left alone.
CHANNEL_GROUP = re.compile(re.escape(os.environ["TARGET_GROUP_PREFIX"]) + r"\d+")


def running_task_ips():
    arns = ecs.list_tasks(cluster=CLUSTER, serviceName=SERVICE, desiredStatus="RUNNING")["taskArns"]
    if not arns:
        return set()
    ips = set()
    for task in ecs.describe_tasks(cluster=CLUSTER, tasks=arns)["tasks"]:
        if task["lastStatus"] != "RUNNING":
            continue
        for attachment in task.get("attachments", []):
            if attachment["type"] != "ElasticNetworkInterface":
                continue
            for detail in attachment["details"]:
                if detail["name"] == "privateIPv4Address":
                    ips.add(detail["value"])
    return ips


def channel_target_groups():
    for page in elbv2.get_paginator("describe_target_groups").paginate():
        for group in page["TargetGroups"]:
            if CHANNEL_GROUP.fullmatch(group["TargetGroupName"]):
                yield group


def handler(event, context):
    ips = running_task_ips()
    print(f"running task IPs: {sorted(ips) or 'none'}")
    for group in channel_target_groups():
        arn, port = group["TargetGroupArn"], group["Port"]
        wanted = {(ip, port) for ip in ips}
        current = {
            (d["Target"]["Id"], d["Target"]["Port"])
            for d in elbv2.describe_target_health(TargetGroupArn=arn)["TargetHealthDescriptions"]
        }
        add, remove = wanted - current, current - wanted
        if add:
            elbv2.register_targets(TargetGroupArn=arn, Targets=[{"Id": i, "Port": p} for i, p in add])
        if remove:
            elbv2.deregister_targets(TargetGroupArn=arn, Targets=[{"Id": i, "Port": p} for i, p in remove])
        if add or remove:
            print(f"{group['TargetGroupName']}: registered {sorted(add)}, deregistered {sorted(remove)}")
