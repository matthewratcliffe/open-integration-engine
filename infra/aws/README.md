# AWS ECS deployment

Terraform deploys one OIE Fargate task per environment. Staging applies on the
default branch; production is a manual GitLab job. The task uses the shared ECS
cluster, ECR repository and HTTPS ALB listener described by
`AWS_SHARED_OUTPUTS_JSON`.

The admin/API endpoint is HTTPS through the shared ALB. EFS persists `appdata`,
including `keystore.jks`, across task replacement. The keystore is seeded once
from Secrets Manager and is never overwritten after it exists on EFS.

Terraform creates TCP target groups for ports 8081 and 6661 and registers the
ECS task with them. It does not change the existing NLB. Every apply prints
`existing_nlb_listener_requirements`; manually configure each listed NLB TCP
listener to forward to its target-group ARN.

## Required GitLab variables

- `AWS_DEPLOY_ROLE_ARN`, `AWS_REGION`, `AWS_ECR_REPOSITORY_URL`
- `AWS_TF_STATE_BUCKET`, `AWS_TF_LOCK_TABLE`
- `AWS_SHARED_OUTPUTS_JSON` with `vpc_id`, `private_subnet_ids`,
  `ecs_cluster_id`, `ecs_cluster_name`, `ecr_repository_url`,
  `alb_security_group_id`, and `alb_https_listener_arn`
- `AWS_ALB_PRIORITY` (environment scoped and unique)
- `AWS_NLB_SUBNET_CIDRS_JSON`, such as `["10.0.1.0/24","10.0.2.0/24"]`
- `OIE_STAGING_HOSTNAME`, `OIE_PRODUCTION_HOSTNAME`
- Environment-scoped `RDS_ENDPOINT`, `RDS_DATABASE_NAME`, `RDS_SECRET_ARN`,
  `RDS_SECURITY_GROUP_ID`, `OIE_ADMIN_PASSWORD`, `KEYSTORE_PASSWORD`, and
  `OIE_KEYSTORE_B64`. The RDS secret must contain `username` and `password`.

Local Kubernetes additionally needs `KUBE_CONFIG_B64`. It deploys one utility
engine and exposes both admin/API and channel traffic using `LoadBalancer`
Services. The cluster needs MetalLB, kube-vip, or another load-balancer
controller.

## AWS access and port forwarding

Use ECS Exec for shell-level diagnostics:

```bash
aws ecs execute-command --cluster <cluster> --task <task-arn> \
  --container oie --interactive --command /bin/bash
```

ECS Exec is not a TCP tunnel. To reach an internal ALB/NLB, use an SSM-managed
bastion in the VPC:

```bash
aws ssm start-session --target <instance-id> \
  --document-name AWS-StartPortForwardingSessionToRemoteHost \
  --parameters '{"host":["<internal-alb-dns>"],"portNumber":["443"],"localPortNumber":["9443"]}'
```

ALB routing and TLS use hostname/SNI, so preserve the real hostname:

```bash
curl --resolve oie-staging.example.com:9443:127.0.0.1 \
  https://oie-staging.example.com:9443/api/server/status \
  -H 'X-Requested-With: operator'
```

For MLLP/raw TCP, tunnel the existing NLB DNS name and listener port. NLB is
layer 4 and has no Host-header routing. Tunnels are for diagnostics, not
production message traffic.
