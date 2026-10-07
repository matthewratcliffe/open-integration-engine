#!/usr/bin/env bash
#
# Deploys this commit to the EC2 instance, from GitLab (the deploy-ec2 job):
#
#   1. copies the installer's settings and the wildcard certificate from
#      GitLab variables into SSM (publish-params.sh)
#   2. builds the bundle (package.sh) and uploads it to this project's
#      generic package registry, as oie-ec2/<commit>/oie-ec2.tar.gz
#   3. has SSM Run Command download it from GitLab on the instance, unpack it
#      into /opt/oie/bundle and run install.sh there
#   4. waits, prints the instance's output, and fails if the install did
#
# Nothing connects to the instance: the SSM agent on it fetches the command.
# The instance downloads the bundle with this job's CI_JOB_TOKEN, which GitLab
# revokes when the job ends -- and the job ends only after the install has.
# The token does stay in the SSM command's history, expired.
#
#   EC2_INSTANCE_NAME    the instance's Name tag (or its i-... id)
#   EC2_SSM_PATH         the parameter path install.sh reads, e.g. /oie/production/oie1
#   the installer's settings    see publish-params.sh for the list
#
# The deploy role needs ec2:DescribeInstances, ssm:DescribeInstanceInformation,
# ssm:PutParameter on <EC2_SSM_PATH>/*, ssm:SendCommand (AWS-RunShellScript,
# the instance) and ssm:GetCommandInvocation. The instance role needs
# ssm:GetParametersByPath on EC2_SSM_PATH, and the instance outbound HTTPS to
# GitLab.

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"

log() { printf '%s [deploy] %s\n' "$(date -u '+%Y-%m-%d %H:%M:%S')" "$*"; }
die() { log "ERROR: $*"; exit 1; }

: "${EC2_INSTANCE_NAME:?set EC2_INSTANCE_NAME -- the Name tag of the instance, scoped to this environment}"
: "${CI_JOB_TOKEN:?run this from a GitLab job: the instance downloads the bundle with its CI_JOB_TOKEN}"
: "${EC2_SSM_PATH:?set EC2_SSM_PATH}"
: "${AWS_REGION:?set AWS_REGION}"
commit="${CI_COMMIT_SHA:-$(git -C "$ROOT" rev-parse HEAD)}"

########################################################################
# Which instance
########################################################################
if [[ "$EC2_INSTANCE_NAME" == i-* ]]; then
    instance="$EC2_INSTANCE_NAME"
else
    mapfile -t ids < <(aws ec2 describe-instances \
        --filters "Name=tag:Name,Values=${EC2_INSTANCE_NAME}" "Name=instance-state-name,Values=running" \
        --query 'Reservations[].Instances[].InstanceId' --output text | tr '\t' '\n' | sed '/^$/d')
    (( ${#ids[@]} == 1 )) \
        || die "expected one running instance named ${EC2_INSTANCE_NAME}, found ${#ids[@]}${ids:+: ${ids[*]}}"
    instance="${ids[0]}"
fi

# The engine's database login on RDS is EC2_INSTANCE_NAME, unless
# DATABASE_USERNAME says otherwise.
export DATABASE_USERNAME="${DATABASE_USERNAME:-$EC2_INSTANCE_NAME}"
log "database user on RDS: ${DATABASE_USERNAME}"
ping="$(aws ssm describe-instance-information --filters "Key=InstanceIds,Values=${instance}" \
    --query 'InstanceInformationList[0].PingStatus' --output text)"
[[ "$ping" == Online ]] \
    || die "${instance} is not reachable through SSM (${ping}) -- the instance role needs AmazonSSMManagedInstanceCore, and the agent outbound HTTPS to the SSM endpoints"
log "deploying ${commit} to ${EC2_INSTANCE_NAME} (${instance})"

########################################################################
# Settings and certificate, bundle
########################################################################
bash "$HERE/publish-params.sh"

bundle="$ROOT/dist/oie-ec2-${commit}.tar.gz"
bash "$HERE/package.sh" "$bundle"
# One package version per commit, so a re-run of an older pipeline installs
# what that commit built.
package_url="${CI_API_V4_URL}/projects/${CI_PROJECT_ID}/packages/generic/oie-ec2/${commit}/oie-ec2.tar.gz"
curl --fail --silent --show-error --retry 3 --header "JOB-TOKEN: ${CI_JOB_TOKEN}"     --upload-file "$bundle" "$package_url" >/dev/null     || die "could not upload the bundle to the package registry"
log "uploaded ${package_url}"

########################################################################
# Install on the instance
########################################################################
# AWS-RunShellScript runs its commands with sh, so the work is a bash script
# fed in on stdin. Values are quoted with %q as they go in.
remote="$(cat <<EOF
bash -s <<'OIE_DEPLOY'
set -euo pipefail
exec > >(tee -a /var/log/oie-install.log) 2>&1
echo "=== \$(date -u '+%Y-%m-%d %H:%M:%S') deploy of ${commit}"
export AWS_REGION=$(printf '%q' "$AWS_REGION")
# The minimal AL2023 image has no tar; the standard one does.
command -v tar >/dev/null || dnf install -y -q tar
tmp="\$(mktemp -d /var/tmp/oie-bundle.XXXXXX)"
trap 'rm -rf "\$tmp"' EXIT
curl --fail --silent --show-error --location --retry 3 \
    --header $(printf '%q' "JOB-TOKEN: ${CI_JOB_TOKEN}") \
    -o "\$tmp/bundle.tar.gz" $(printf '%q' "$package_url") \
    || { echo "could not download the bundle from GitLab -- does this instance reach $(printf '%q' "${CI_SERVER_HOST:-GitLab}") on 443?" >&2; exit 1; }
mkdir "\$tmp/x"
tar -xzf "\$tmp/bundle.tar.gz" -C "\$tmp/x"
mkdir -p /opt/oie
rm -rf /opt/oie/bundle.previous
if [[ -d /opt/oie/bundle ]]; then mv /opt/oie/bundle /opt/oie/bundle.previous; fi
mv "\$tmp/x/oie-ec2" /opt/oie/bundle
OIE_SSM_PATH=$(printf '%q' "$EC2_SSM_PATH") /opt/oie/bundle/install.sh </dev/null
OIE_DEPLOY
EOF
)"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
jq -n --arg id "$instance" --arg script "$remote" --arg comment "OIE ${commit:0:12} from GitLab ${CI_PIPELINE_ID:-}" '{
    DocumentName: "AWS-RunShellScript",
    InstanceIds: [$id],
    Comment: $comment,
    Parameters: {commands: [$script], executionTimeout: ["1800"]}
}' > "$work/command.json"
command_id="$(aws ssm send-command --cli-input-json "file://$work/command.json" \
    --query Command.CommandId --output text)"
log "SSM command ${command_id} sent; waiting for the install"

status=Pending
for _ in $(seq 1 220); do
    status="$(aws ssm get-command-invocation --command-id "$command_id" --instance-id "$instance" \
        --query Status --output text 2>/dev/null || echo Pending)"
    case "$status" in
        Pending|InProgress|Delayed) sleep 10 ;;
        *) break ;;
    esac
done

# SSM keeps the first 24,000 characters of each stream; the whole log is in
# /var/log/oie-install.log on the instance.
aws ssm get-command-invocation --command-id "$command_id" --instance-id "$instance" \
    --query StandardOutputContent --output text || true
errors="$(aws ssm get-command-invocation --command-id "$command_id" --instance-id "$instance" \
    --query StandardErrorContent --output text 2>/dev/null || true)"
[[ -z "$errors" || "$errors" == None ]] || printf '%s\n' "$errors" >&2

[[ "$status" == Success ]] || die "install on ${instance} finished ${status}"
log "deployed ${commit} to ${EC2_INSTANCE_NAME} (${instance})"
