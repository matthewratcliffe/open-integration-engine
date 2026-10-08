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
deploy/ec2/publish-params.sh  copies the settings and certificate from GitLab into SSM
```

## How a deploy reaches the instance

Nothing connects to the instance. The manual `deploy-ec2-production-oie1` job,
on a `main` pipeline, signs in to AWS with the existing GitLab deploy role and:

1. looks up the instance by its `Name` tag (`EC2_INSTANCE_NAME`) and checks
   SSM can reach it;
2. copies the installer's settings from GitLab variables into SSM Parameter
   Store under `EC2_SSM_PATH`, and the shared `*.htrak.com` wildcard
   (`TLS_CERT_PEM`/`TLS_KEY_PEM`) after checking the key matches and the
   certificate has not expired;
3. builds the bundle - the installer, the entrypoint, the scripts, the engine
   release, the plugins and the community extensions, each verified against its pinned checksum - and
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
    "Resource": ["arn:aws:ssm:<region>:<account>:parameter/oie/production/oie1",
                 "arn:aws:ssm:<region>:<account>:parameter/oie/production/oie1/*"] }
  ```

  and `kms:Decrypt` if the parameters use a customer-managed key.
- **The CI deploy role** (defined in `infra/awsshardmoduleprod`):
  `ec2:DescribeInstances`, `ssm:DescribeInstanceInformation`,
  `ssm:PutParameter` on
  `<path>/*`, `ssm:SendCommand` on the `AWS-RunShellScript` document and
  the instance, and `ssm:GetCommandInvocation`.
- **Package registry space**: each deploy stores a bundle of about 245 MB.
  Delete old `oie-ec2` versions from Deploy > Package registry now and then;
  the job token cannot delete them itself.
- **Security groups**: RDS allows 5432 from the instance; the instance allows
  443 (console and API, through nginx) and 80 (redirect only) from your admin
  ranges, plus the ports your channels listen on. Not 8443: the engine listens
  on loopback only.
- **DNS**: `oie1.htrak.com` pointing at the instance's Elastic IP.

## Variables

In GitLab, scoped to the environment `production/oie1`:

| Variable | |
| --- | --- |
| `EC2_INSTANCE_NAME` | the instance's `Name` tag (an `i-...` id also works). Exactly one running instance must match |
| `EC2_SSM_PATH` | the parameter path the instance reads, e.g. `/oie/production/oie1`. One per instance: everything beneath it is read |

The installer's settings are GitLab variables too, in the same environment.
Each deploy copies the ones below into SSM under `EC2_SSM_PATH`, as
SecureString parameters named after the variable; one not set in GitLab is
left as it is in SSM, so a parameter put there by hand still works.
`TLS_CERT_PEM`/`TLS_KEY_PEM` are the existing instance-level variables.

Required:

| Variable | |
| --- | --- |
| `RDS_ENDPOINT` | the RDS instance's endpoint, `host` or `host:port`. Defaults to the shared RDS instance in `shared-outputs.json`. Or give the whole `DATABASE_URL` instead |
| `DATABASE_PASSWORD` | password for the engine's database user |
| `OIE_ADMIN_PASSWORD` | the `admin` account's password, rotated from 4.6.0's `admin` on first boot |
| `KEYSTORE_PASSWORD` | guards `appdata/keystore.jks`. **Cannot change after first boot** |

Optional:

| Variable | |
| --- | --- |
| `DATABASE_USERNAME` | the engine's login on RDS. Defaults to `EC2_INSTANCE_NAME` |
| `POSTGRES_DB` | database name, default `mirthdb`. Not the database another engine uses: `RDS_DATABASE_NAME`, the ECS engine's, is deliberately not copied |
| `RDS_MASTER_USERNAME`, `RDS_MASTER_PASSWORD` | the installer creates the user and database on RDS with them (idempotently). Default to the shared outputs' credentials, decrypted with `ENCRYPTION_KEY` as the ECS plan does |
| `KEYSTORE_BASE64` | a keystore to install when the instance has none, `base64 -w0 keystore.jks`. Defaults to `OIE_KEYSTORE_B64`. Recommended: see below |
| `KEYSTORE_RESET` | set to a new value to deliberately replace the keystore, as on ECS |
| `OIE_HEAP_MAX` | default `1g` |
| `HTTPS_PORT` | the port nginx serves the console and API on, default `443`. The engine itself is on `127.0.0.1:8443` |
| `HTTP_REDIRECT` | default `true`: nginx on port 80 answers every request with a redirect to HTTPS, and serves nothing else. `false` closes 80 |
| `WEB_LOCAL_LOGIN` | default `true`. `false` turns off password sign-in on the web administrator, leaving it SSO only. See [Ports and the proxy](#ports-and-the-proxy) |
| `API_DOCS_REQUIRE_SSO` | default `true`: the API documentation only for a signed-in SSO account. `false` serves it to anyone, as the engine does |
| `OIE_EXTENSION_URLS` | the community extensions, replacing the whole list. Defaults to ECS's `extension_urls` (`infra/aws/variables.tf`): Web Support, which serves `/oie-webadmin/`, Sentinel, Thread Viewer, TLS Manager and OIDC auth. Bundled at deploy time, not copied to SSM |
| `OIE_UPDATE_CHECK`, `TZ` | as in `.env.example` |

Anything else the entrypoint understands (`OIE_OIDC_*`, `_MP_*`, ...) works as
an SSM parameter put there by hand; the deploy copies only the names above.

### Single sign-on

The same `OIDC_*` variables as the ECS deploy (`infra/aws/README.md`,
`docs/sso.md`), with the same checks. `OIDC_ENABLED` unset leaves SSO to the
console. Set, the deploy pins the provider settings - `OIDC_ENABLED`,
`OIDC_DISCOVERY_URL`, `OIDC_CLIENT_ID`, `OIDC_CLIENT_SECRET`, and optionally
`OIDC_PROVIDER_LABEL`, `OIDC_USERNAME_CLAIM`, `OIDC_SCOPES`,
`OIDC_AUTO_REDIRECT`, plus JIT provisioning, on unless `OIDC_JIT_ENABLED=false`
(see `infra/aws/README.md` for what that admits) - and derives the web administrator URL from
`EC2_HOSTNAME` (set by the job, `oie1.htrak.com`), so the redirect URI to
register at the provider is `https://oie1.htrak.com/oie-webadmin/oidc/callback`.
The job log prints it. It differs from the ECS engine's (`oie.htrak.com`), so a
shared app registration needs both.

They travel as one SecureString parameter, `OIE_OIDC_SETTINGS`, a JSON object
of `OIE_OIDC_*` overrides rewritten on every deploy, so a variable removed in
GitLab is removed from the engine too. While it pins anything, it owns those
`OIE_OIDC_*` names, and a hand-set parameter for one of them is ignored.

### Git Sync

The Git Sync extension's settings (Settings > Git Sync) can come from GitLab
too. After the admin bootstrap, the installer runs
`scripts/oie-gitsync-configure.sh`, which sends the ones set to the
extension's settings API. One not set stays as the console has it; one set is
applied on every deploy, so a console edit of it lasts until the next one.

| Variable | |
| --- | --- |
| `GIT_SYNC_REMOTE_URL` | the configuration repository, `https://...` or `git@host:group/repo.git` |
| `GIT_SYNC_BRANCH` | branch to follow; the extension's default is `main` |
| `GIT_SYNC_AUTH_TYPE` | `none`, `https_token` or `ssh_key` |
| `GIT_SYNC_USERNAME` | HTTPS username; for a GitLab token, anything non-empty such as `oauth2` |
| `GIT_SYNC_SECRET` | the token, or the SSH private key (multi-line; mask it). Stored encrypted by the extension and never returned; not set keeps the stored one |
| `GIT_SYNC_MODE` | `read_only` (the default: pull only) or `read_write` |
| `GIT_SYNC_SUBDIRECTORY` | path in the repository for this engine, when one repository holds several |
| `GIT_SYNC_AUTHOR_NAME`, `GIT_SYNC_AUTHOR_EMAIL` | who commits from this engine |
| `GIT_SYNC_PULL_INTERVAL_SECONDS` | scheduled pull; `0` turns it off |
| `GIT_SYNC_SCOPE` | comma-separated: `channels`, `code-templates`, `channel-groups`, `configuration-map`, `alerts`, `global-scripts`, `server-settings`, `administrator-settings`, `channel-tags`, `resources`, `data-pruner`, `volume-monitor`. Empty is the extension's default set |
| `GIT_SYNC_KNOWN_HOSTS` | `known_hosts` lines for an SSH remote; without them host keys are not checked |

An unrecognised auth type, mode or interval fails the deploy before anything
is sent, as does the extension refusing the settings or not being installed. A
field it cannot use (it keeps the old value) and a remote it cannot reach yet
only warn, with the reason. The same
script works against any engine:
`OIE_URL=https://host/api OIE_PASSWORD=... GIT_SYNC_...=... ./scripts/oie-gitsync-configure.sh`.

The engine release and plugin list are the ones `docker/Dockerfile` pins, at
the commit being deployed.

## Deploying

1. Set the variables, and create the roles and the instance.
2. Run `deploy-ec2-production-oie1` on a `main` pipeline. The first deploy
   creates the schema on RDS, which takes a few minutes; it finishes when the
   admin password has been rotated.
3. Browse to `https://oie1.htrak.com/` (or plain `http://`, which redirects),
   or `/oie-webadmin/` with the web administrator extension installed, and push
   configuration as usual:

   ```
   OIE_URL=https://oie1.htrak.com/api OIE_PASSWORD=... \
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

## Ports and the proxy

nginx serves the console and API on 443 with the certificate below and proxies
to the engine, which listens on `127.0.0.1:8443` only, so nothing reaches it
without passing nginx. A bare `https://oie1.htrak.com/` goes to
`/oie-webadmin/`, as on ECS. Port 80 only redirects to
`https://<host><path>`. The engine's own plaintext listener (`HTTP_PORT`) stays
off, as in compose. Channel listener ports are the engine's own, not proxied.

nginx asks `oie-gate` (`deploy/ec2/oie-gate.py`, a small Python service on
`127.0.0.1:8441`) about two things first:

- **The API documentation** (`API_DOCS_REQUIRE_SSO=true`): the Swagger UI at
  `/api/` and its assets, `/api/openapi.json|yaml`, `/apiexamples` and
  `/javadocs`. It is served only when the browser's engine session belongs to
  an account bound to the SSO provider (one with the OIDC extension's
  `oidc.subject` preference). No session redirects to the console to sign in;
  a local account, `admin` included, gets a 403. The API itself is not gated.
- **Password sign-in on the web administrator** (`WEB_LOCAL_LOGIN=false`):
  the console's sign-in goes through the gate, which refuses any that is not
  the OIDC extension's ticket, and the login card shows why. Set
  `OIDC_AUTO_REDIRECT=true` too, so the card goes straight to the provider
  rather than showing a password form that will be refused.

  This turns off a way in, not the accounts: the REST API, the Swing
  Administrator and the scripts here still sign in with a password, which is
  the break-glass path when the provider is down. Restricting those means
  restricting who reaches 443 (the security group). With SSO not pinned on,
  the installer warns that nobody may be able to sign in to the console.

To make the docs check work on `/apiexamples` and `/javadocs`, nginx widens the
engine's session cookie from `Path=/api` to `Path=/`. The engine sees nginx's
address as the client's; the real one is in `X-Forwarded-For` and
`/var/log/nginx/access.log`.

## The certificate

nginx serves the wildcard from `/etc/oie/tls`; without it, a self-signed
certificate of its own in `/etc/oie/proxy`. On each start, the engine's start
script also puts the certificate from
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
| `/etc/nginx/nginx.conf` | the proxy and the port 80 redirect, rewritten by each deploy |
| `/etc/systemd/system/oie-gate.service` | the proxy's gate; `journalctl -u oie-gate` logs refused sign-ins |
| `/var/log/nginx/` | the proxy's access and error logs |
| `journalctl -u oie -f` | startup and the entrypoint's output |
| `/opt/engine/logs/mirth.log` | the engine's own log |
| `/var/log/oie-install.log` | every deploy's full output |
