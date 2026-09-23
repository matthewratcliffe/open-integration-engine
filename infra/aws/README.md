# AWS ECS deployment

Terraform deploys one OIE Fargate task per environment. Staging applies on the
GitLab default branch; production is a manual job. The shared ECS, ALB, NLB,
VPC, subnets, ECR and RDS values come from the `shared-outputs.json` artifact
fetched from `infra/awsshardmoduleprod`.

The admin/API endpoint uses the shared ALB. EFS persists OIE `appdata`, including
`keystore.jks`, across task replacement. Terraform creates TCP target groups for
ports 8081 and 6661 (channel traffic - HTTP and MLLP by default) and owns their
listeners directly on the shared NLB (`load_balancing.tf`'s `aws_lb_listener.channel`),
since the ECS service requires each target group to already have an associated
load balancer at creation time. The NLB is shared with other apps, so this
requires `nlb_arn` from `awsshardmoduleprod`'s shared output, and the NLB's own
security group (`awsshardmoduleprod`) needs inbound rules for these ports for
external channel clients to actually reach them.

The Terraform state backend (S3 bucket, key, region, locking) is defined per
environment in `environments/<env>.backend.hcl` - a checked-in file, not a
CI/CD variable, following the same pattern as `awsshardmoduleprod`.

Required GitLab variables:

- `AWS_REGION` (defaults to `ap-southeast-2` if unset)
- `AWS_ALB_PRIORITY`, `OIE_STAGING_HOSTNAME`, `OIE_PRODUCTION_HOSTNAME`
- `ENCRYPTION_KEY` (instance-level) to decrypt the RDS master username/password
  published (encrypted) by `awsshardmoduleprod` in `shared-outputs.json`; set
  `RDS_MASTER_USERNAME`/`RDS_MASTER_PASSWORD` directly to override.
- Environment-scoped `RDS_DATABASE_NAME`; the RDS endpoint, RDS security group,
  NLB security group, ECS cluster, ALB listener, subnets and ECR repository
  are read from the shared artifact.
  If the fetched artifact omits the RDS security-group ID, set `RDS_SECURITY_GROUP_ID` explicitly.
- `OIE_ADMIN_PASSWORD`, `KEYSTORE_PASSWORD`, and `OIE_KEYSTORE_B64`.
  The RDS secret must contain `username` and `password`.

Local Kubernetes additionally needs `KUBE_CONFIG_B64`, `DATABASE_URL`,
`RDS_MASTER_USERNAME`, and `RDS_MASTER_PASSWORD`. It deploys one utility engine;
channel traffic is exposed via a `LoadBalancer` Service (the cluster needs
MetalLB, kube-vip, or another load-balancer controller), and the admin/API web
UI is exposed via an nginx Ingress at `oie-dc.htrak.com`, using the
`TLS_CERT_PEM`/`TLS_KEY_PEM` instance-level CI/CD variables (the shared
`*.htrak.com` cert) for its TLS secret.

## AWS access and port forwarding

Use ECS Exec for shell-level diagnostics:

```bash
aws ecs execute-command --cluster <cluster> --task <task-arn> \
  --container oie --interactive --command /bin/bash
```

ECS Exec is not a TCP tunnel. To reach an internal ALB/NLB from a workstation,
use an SSM-managed bastion in the VPC:

```bash
aws ssm start-session --target <instance-id> \
  --document-name AWS-StartPortForwardingSessionToRemoteHost \
  --parameters '{"host":["<internal-alb-dns>"],"portNumber":["443"],"localPortNumber":["9443"]}'
```

ALB routing and TLS use hostname/SNI, so preserve the real hostname when testing:

```bash
curl --resolve oie-staging.example.com:9443:127.0.0.1 \
  https://oie-staging.example.com:9443/api/server/status \
  -H 'X-Requested-With: operator'
```

For MLLP/raw TCP, tunnel the existing NLB DNS name and listener port. NLB is
layer 4 and has no Host-header routing. Tunnels are for diagnostics, not
production message traffic.
