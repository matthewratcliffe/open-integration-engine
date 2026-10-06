# AWS ECS deployment

Terraform deploys one OIE Fargate task per deployment - an environment plus an
instance. Staging is the single instance `oie` (`au-staging-oie`) and applies on
the GitLab default branch. Production runs numbered instances - `oie1` at
`oie1.htrak.com` today, `oie2` and so on later - each a separate engine with its
own service, EFS, database, keystore, secret, state and NLB port range, deployed
by its own manual job. Each instance's variables are scoped to the GitLab
environment `production/<instance>`.

To add a production instance (e.g. `oie2`):

1. Copy the `plan-aws-production-oie1` / `deploy-aws-production-oie1` jobs in
   `.gitlab-ci.yml`, changing `DEPLOY_INSTANCE` (and the `needs` job name).
2. Add `environments/production-oie2.backend.hcl` with its own state `key`.
3. Add a `"production/oie2"` NLB port range to `locals.channel_port_ranges`.
4. Set `RDS_DATABASE_NAME`, `AWS_ALB_PRIORITY`, `AWS_ALB_REDIRECT_PRIORITY`,
   `KEYSTORE_PASSWORD` and `OIE_KEYSTORE_B64` scoped to `production/oie2`, with
   unused ALB priorities (the plan job lists the listener's rules).
5. Point DNS for `oie2.htrak.com` at the shared ALB.

The hostname is `<instance>.htrak.com`. The shared ECS, ALB, NLB,
VPC, subnets, ECR and RDS values come from the `shared-outputs.json` artifact
fetched from `infra/awsshardmoduleprod`.

The admin/API endpoint uses the shared ALB. EFS persists OIE `appdata`, including
`keystore.jks`, across task replacement.

Channel traffic goes through the shared NLB. Each deployment reserves 100 ports
(`locals.channel_port_ranges`: staging/oie 50000-50099, production/oie1
50500-50599), and the plan job fails if another app has a listener anywhere in
its range. An NLB listener serves exactly one port, so only the first
`CHANNEL_PORT_COUNT` ports of the range (default 4) are open, each with its own
listener and target group; raise it per environment to open more. Open ports
are public (`0.0.0.0/0`) unless `CHANNEL_PORT_SOURCES` restricts them, as
comma-separated `port=source` pairs (e.g. `50003=203.0.113.7,50004=10.1.0.0/16`;
a bare address means that host). A restricted port also allows the trusted
ranges. Ports sharing the same sources share NLB security-group rules, so by
default there's one.

An ECS service attaches at most 5 target groups, so the running task is
registered in the channel target groups by a small Lambda
(`channel_targets.tf`, `lambda/channel_targets.py`) instead. It reconciles
every channel target group to the service's running task on each task state
change and every 5 minutes; its logs are in `/aws/lambda/<name>-channel-targets`.
The CI deploy role needs Lambda and EventBridge permissions for it. The shared
NLB's default quota is 50 listeners, shared with the other apps on it - check
there's room before adding instances or ports.

The task only accepts connections from the shared ALB (8443) and NLB (the
reserved channel range), never directly. The console can be limited to
trusted sources; channel ports through the NLB are public unless restricted:

- When `TRUSTED_CIDRS` is set, those ranges are the only sources that reach the
  console on the shared ALB (443): both admin listener rules carry a
  `source_ip` condition, so anyone else falls through to the listener's other
  rules and default action. The ALB's own security group is shared with other
  apps and is left alone. An ALB rule takes at most 5 condition values, and the
  `/` redirect rule already uses two, so there can be at most 3 ranges. With
  `TRUSTED_CIDRS` unset the rules have no source condition and the console is
  public.
- Open channel ports allow 0.0.0.0/0 through the NLB unless
  `CHANNEL_PORT_SOURCES` restricts them, and a restricted port still allows
  the trusted ranges.

Each environment's database (`RDS_DATABASE_NAME`) on the shared RDS instance is
created by Terraform during apply (`database.tf`): it runs a one-off `db-init`
Fargate task in the VPC and fails the apply if the database can't be created.
This needs the `aws` CLI in the apply job and `ecs:RunTask`/`ecs:DescribeTasks`
(plus `iam:PassRole` on the execution role) for the CI deploy role. The task's
logs are in the service's CloudWatch log group under the `db-init` prefix.

The Terraform state backend (S3 bucket, key, region, locking) is defined per
deployment in `environments/<env>-<instance>.backend.hcl` - a checked-in file, not a
CI/CD variable, following the same pattern as `awsshardmoduleprod`.

Required GitLab variables:

- `AWS_REGION` (defaults to `ap-southeast-2` if unset)
- Environment-scoped `AWS_ALB_PRIORITY`; `OIE_STAGING_HOSTNAME` (production
  hostnames are `<instance>.htrak.com`)
- Environment-scoped `AWS_ALB_REDIRECT_PRIORITY`: the shared-listener priority
  of the rule redirecting a bare `/` to `/oie-webadmin/`. It must be lower than
  `AWS_ALB_PRIORITY` and unused by other apps (staging 105, production/oie1 106).
- `ENCRYPTION_KEY` (instance-level) to decrypt the RDS master username/password
  published (encrypted) by `awsshardmoduleprod` in `shared-outputs.json`; set
  `RDS_MASTER_USERNAME`/`RDS_MASTER_PASSWORD` directly to override.
- Environment-scoped `RDS_DATABASE_NAME`; the RDS endpoint, RDS security group,
  NLB security group, ECS cluster, ALB listener, subnets and ECR repository
  are read from the shared artifact.
  If the fetched artifact omits the RDS security-group ID, set `RDS_SECURITY_GROUP_ID` explicitly.
- Optional environment-scoped `TRUSTED_CIDRS`: up to 3 comma-separated
  addresses or CIDRs (see above). Unset leaves the console public.
- `OIE_ADMIN_PASSWORD`, `KEYSTORE_PASSWORD`, and `OIE_KEYSTORE_B64`.
  The RDS secret must contain `username` and `password`.
  The plan job fails if `KEYSTORE_PASSWORD` does not open `OIE_KEYSTORE_B64`.
  The supplied keystore is installed only when EFS has none yet: after first
  boot the keystore on EFS also holds the engine's data-encryption key, so it
  is never silently replaced.
- Optional environment-scoped `KEYSTORE_RESET` to replace an environment's
  keystore deliberately. Set it to a new value (e.g. `2026-10-05`); on its next
  start the engine moves `appdata/keystore.jks` to
  `keystore.jks.replaced-<value>` and installs `OIE_KEYSTORE_B64` (or generates
  one). Data encrypted under the old keystore is unreadable until it is moved
  back, so only do this where that is acceptable.
- Optional environment-scoped `CHANNEL_PORT_COUNT` and `CHANNEL_PORT_SOURCES`:
  how many channel ports to open, and per-port source restrictions (see above).
- Optional environment-scoped single sign-on (`docs/sso.md`). Leave
  `OIDC_ENABLED` unset and SSO is configured in the console. Set it to `true`
  (or `false`, to pin SSO off) and the deployment pins the policy instead:
  - `OIDC_DISCOVERY_URL`, `OIDC_CLIENT_ID` and `OIDC_CLIENT_SECRET` (masked)
    are required with `OIDC_ENABLED=true`. The secret goes into the Secrets
    Manager secret, not the task's plain environment.
  - `OIDC_PROVIDER_LABEL`, `OIDC_USERNAME_CLAIM` and `OIDC_SCOPES` are
    optional, and pinned only when set.
  - The web administrator URL is derived as `https://<hostname>/oie-webadmin`;
    register `https://<hostname>/oie-webadmin/oidc/callback` as the redirect
    URI at the provider (the plan job prints it).
  - `OIDC_AUTO_REDIRECT=true` sends browsers straight to the provider instead
    of showing the password form. It **hides** local sign-in, it does not
    disable it: the REST API and the admin account still accept a password,
    which is the break-glass path when the provider is down.

  A pinned field is read-only in the console. Role mappings, linked accounts
  and JIT provisioning are not pinned, and stay in the console. Any `OIDC_*`
  variable set without `OIDC_ENABLED` fails the plan rather than being
  ignored.

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
