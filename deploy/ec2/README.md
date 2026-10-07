# EC2 deployment, without Docker

One engine installed straight onto an EC2 instance as a systemd service, with
its database on RDS, deployed from GitLab. It runs the same release, the same
13 built-in plugins and the same `docker/entrypoint.sh` as the image, so every
variable in `.env.example` means the same thing here. In production it is
`oie1.htrak.com`; the ECS deployment that used that name is at
`oie.htrak.com`.

```
deploy/ec2/deploy.sh       what the deploy-ec2-production-oie1 job runs
deploy/ec2/package.sh      builds the installer bundle
deploy/ec2/install.sh      the installer, run on the instance from the bundle
deploy/ec2/publish-tls.sh  copies the wildcard certificate into SSM
```

## How a deploy reaches the instance

Nothing connects to the instance. The manual `deploy-ec2-production-oie1` job,
on a `main` pipeline, signs in to AWS with the existing GitLab deploy role and:

1. looks up the instance by its `Name` tag (`EC2_INSTANCE_NAME`) and checks
   SSM can reach it;
2. writes `TLS_CERT_PEM`/`TLS_KEY_PEM`, the shared `*.htrak.com` wildcard, to
   SSM Parameter Store, after checking the key matches and the certificate has
   not expired;
3. builds the bundle - the installer, the entrypoint, the scripts, the engine
   release and the plugins, each verified against its pinned checksum - and
   uploads it to this project's generic package registry as
   `oie-ec2/<commit>/oie-ec2.tar.gz` (Deploy > Package registry);
4. sends an SSM Run Command. The SSM agent on the instance picks it up,
   downloads the bundle from GitLab with the job's `CI_JOB_TOKEN`, unpacks it
   into `/opt/oie/bundle` and runs `install.sh`, which reads its settings from
   Parameter Store with the instance role;
5. waits, prints the instance's output in the job log, and fails if the
   install failed.

So the instance needs outbound HTTPS to `gitlab.htrak.com` and the SSM
endpoints, and no access to GitHub. The job token stops working when the job
ends, which is after the install; it stays in the SSM command's history, but
expired.

## What you need

- **The instance**: Amazon Linux 2023, t3.medium or larger, with a `Name` tag.
  Give `OIE_HEAP_MAX` about half its memory. No user data is needed.
- **Its role**: `AmazonSSMManagedInstanceCore`, plus

  ```json
  { "Effect": "Allow", "Action": "ssm:GetParametersByPath",
    "Resource": "arn:aws:ssm:<region>:<account>:parameter/oie/production" }
  ```

  and `kms:Decrypt` if the parameters use a customer-managed key.
- **The CI deploy role** (defined in `infra/awsshardmoduleprod`):
  `ec2:DescribeInstances`, `ssm:DescribeInstanceInformation`,
  `ssm:PutParameter` on
  `<path>/TLS_*`, `ssm:SendCommand` on the `AWS-RunShellScript` document and
  the instance, and `ssm:GetCommandInvocation`.
- **Package registry space**: each deploy stores a bundle of about 245 MB.
  Delete old `oie-ec2` versions from Deploy > Package registry now and then;
  the job token cannot delete them itself.
- **Security groups**: RDS allows 5432 from the instance; the instance allows
  8443 (console and API) from your admin ranges, plus the ports your channels
  listen on.
- **DNS**: `oie1.htrak.com` pointing at the instance's Elastic IP.

## Variables

In GitLab, scoped to the environment `production/oie1`:

| Variable | |
| --- | --- |
| `EC2_INSTANCE_NAME` | the instance's `Name` tag (an `i-...` id also works). Exactly one running instance must match |
| `EC2_SSM_PATH` | the parameter path the instance reads, e.g. `/oie/production` |

`TLS_CERT_PEM`/`TLS_KEY_PEM` are the existing instance-level variables.

In SSM Parameter Store under `EC2_SSM_PATH`, as SecureString, one parameter
per variable (`/oie/production/OIE_ADMIN_PASSWORD`, ...). Required:

| Variable | |
| --- | --- |
| `RDS_ENDPOINT` | the RDS instance's endpoint, `host` or `host:port`. Or give the whole `DATABASE_URL` instead |
| `DATABASE_PASSWORD` | password for the engine's database user |
| `OIE_ADMIN_PASSWORD` | the `admin` account's password, rotated from 4.6.0's `admin` on first boot |
| `KEYSTORE_PASSWORD` | guards `appdata/keystore.jks`. **Cannot change after first boot** |

Optional:

| Variable | |
| --- | --- |
| `DATABASE_USERNAME` | default `mirthdb` |
| `POSTGRES_DB` | database name, default `mirthdb`. Not the database another engine uses |
| `RDS_MASTER_USERNAME`, `RDS_MASTER_PASSWORD` | when set, the installer creates the user and database on RDS (idempotently). Otherwise both must exist already |
| `KEYSTORE_BASE64` | a keystore to install when the instance has none, `base64 -w0 keystore.jks`. Recommended: see below |
| `KEYSTORE_RESET` | set to a new value to deliberately replace the keystore, as on ECS |
| `OIE_HEAP_MAX` | default `1g` |
| `HTTPS_PORT` | console and API port, default `8443`. `443` works too |
| `OIE_EXTENSION_URLS`, `OIE_OIDC_*`, `_MP_*`, ... | anything else from `.env.example` or the entrypoint passes straight through |

The engine release and plugin list are the ones `docker/Dockerfile` pins, at
the commit being deployed.

## Deploying

1. Create the parameters, the roles and the instance.
2. Run `deploy-ec2-production-oie1` on a `main` pipeline. The first deploy
   creates the schema on RDS, which takes a few minutes; it finishes when the
   admin password has been rotated.
3. Browse to `https://oie1.htrak.com:8443/`, or `/oie-webadmin/` with the web
   administrator extension installed, and push configuration as usual:

   ```
   OIE_URL=https://oie1.htrak.com:8443/api OIE_PASSWORD=... \
       ./scripts/oie-config-push.sh
   ```

Run the job again to deploy a new commit, to apply a changed parameter, or
after the wildcard is renewed. The installer rewrites `/etc/oie/oie.env`,
reinstalls the release only if the bundle's release or plugins changed, and
restarts the engine; `appdata/` and `logs/` are left alone. To re-apply the
current bundle by hand, from a Session Manager shell:

```
sudo /opt/oie/bundle/install.sh
```

It remembers `OIE_SSM_PATH` from the last run.

## The certificate

On each start, the engine's start script puts the certificate from
`/etc/oie/tls` into `appdata/keystore.jks` under the alias the engine serves
(`mirthconnect`), only when it differs from the one already there. Every other
entry, including the data-encryption key, is left as it is. The previous
keystore is kept as `keystore.jks.before-tls`, and a failed import puts it back
and starts on the old certificate. Without the parameters the engine serves
its own self-signed certificate.

## The keystore

`/opt/engine/appdata/keystore.jks` holds the engine's TLS certificate and the
key its encrypted data is written under. It lives on the instance's root
volume, so a replacement instance would generate a new one and could not read
anything encrypted under the old one. Either snapshot the volume, or extract
the keystore once and store it as `KEYSTORE_BASE64`, so any instance built from
these parameters comes up with the same key:

```
sudo base64 -w0 /opt/engine/appdata/keystore.jks
```

A keystore is usually 3-6 KB once encoded, over a standard parameter's 4 KB
limit, so store it as an Advanced parameter (`--tier Advanced`).

## Where things are

| | |
| --- | --- |
| `/opt/engine` | the engine, owned by the `engine` user |
| `/opt/oie/bundle` | the bundle last deployed (`bundle.previous`, the one before) |
| `/etc/oie/oie.env` | the rendered environment, root-only |
| `/etc/oie/tls/` | the certificate and key from SSM, readable by the engine only |
| `/etc/systemd/system/oie.service` | the service |
| `journalctl -u oie -f` | startup and the entrypoint's output |
| `/opt/engine/logs/mirth.log` | the engine's own log |
| `/var/log/oie-install.log` | every deploy's full output |
