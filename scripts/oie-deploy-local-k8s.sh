#!/usr/bin/env bash
set -euo pipefail

# Deploy a single-node OIE cluster to the local-k8s environment.
#
# Two things this script is careful about, both for the same reason -- the
# engine's data-encryption key lives in the keystore, and its database holds
# everything encrypted with that key:
#
#   1. The keystore is generated ONCE and then reused forever. On the first
#      deploy, if no keystore is supplied (OIE_KEYSTORE_B64) and none already
#      exists in the cluster, one is generated with keytool. On every later
#      deploy the existing oie-keystore Secret is left untouched -- because an
#      engine that gets a different key cannot read what it previously wrote.
#
#   2. The database defaults to the in-cluster Postgres (deploy/k8s/postgres.yaml,
#      wired by deploy/k8s/config.yaml as jdbc:postgresql://oie-db:5432/mirthdb).
#      Only when RDS variables are supplied does it point at an external database
#      instead -- that is the AWS path, and it is optional here.
#
# The upshot: local-k8s deploys with NO required secrets. Set OIE_KEYSTORE_B64,
# KEYSTORE_PASSWORD, OIE_ADMIN_PASSWORD, DATABASE_URL, RDS_MASTER_* to override
# the generated / in-cluster defaults; leave them unset for a batteries-included
# local deploy.

if [[ -z "${KUBE_CONFIG_B64:-}" ]]; then
  echo "KUBE_CONFIG_B64 is unavailable in this job. Set it as a masked GitLab variable with environment scope local-k8s (or *); if Protected, protect the default branch." >&2
  exit 1
fi
: "${IMAGE_TAG:?required}"

mkdir -p "$HOME/.kube"
if [[ -f "$KUBE_CONFIG_B64" ]]; then
  cp "$KUBE_CONFIG_B64" "$HOME/.kube/config"
else
  printf '%s' "$KUBE_CONFIG_B64" | base64 -d > "$HOME/.kube/config"
fi
chmod 600 "$HOME/.kube/config"

ns="${OIE_NAMESPACE:-oie-local}"
kubectl create namespace "$ns" --dry-run=client -o yaml | kubectl apply -f -

kubectl -n "$ns" create secret docker-registry gitlab-registry \
  --docker-server="$CI_REGISTRY" --docker-username="$CI_REGISTRY_USER" --docker-password="$CI_REGISTRY_PASSWORD" \
  --dry-run=client -o yaml | kubectl apply -f -

# --- Read whatever state the cluster already holds -----------------------------
# An existing keystore or password must win over anything generated here: the key
# is the one piece of state we must never change under a running engine.
existing_keystore_b64="$(kubectl -n "$ns" get secret oie-keystore -o jsonpath='{.data.keystore\.jks}' 2>/dev/null || true)"

secret_get() {
  # secret_get <secret> <key> -> decoded value, empty if absent
  local raw
  raw="$(kubectl -n "$ns" get secret "$1" -o "jsonpath={.data.$2}" 2>/dev/null || true)"
  if [[ -n "$raw" ]]; then
    printf '%s' "$raw" | base64 -d
  fi
}
existing_keystore_password="$(secret_get oie-secrets KEYSTORE_PASSWORD)"
existing_admin_password="$(secret_get oie-secrets OIE_ADMIN_PASSWORD)"
existing_db_password="$(secret_get oie-secrets POSTGRES_PASSWORD)"

gen_password() { openssl rand -base64 24 | tr -d '\n/+=' | cut -c1-24; }

# --- Resolve the keystore password ---------------------------------------------
# Precedence: existing cluster value > CI variable > generated. It MUST match the
# password the keystore was created with, so if we reuse an existing keystore we
# must also reuse its password.
if [[ -n "$existing_keystore_password" ]]; then
  keystore_password="$existing_keystore_password"
elif [[ -n "${KEYSTORE_PASSWORD:-}" ]]; then
  keystore_password="$KEYSTORE_PASSWORD"
else
  keystore_password="$(gen_password)"
  echo "No KEYSTORE_PASSWORD supplied and none in the cluster -- generated one."
fi

# --- Resolve the keystore itself -----------------------------------------------
keyfile="$(mktemp)"; trap 'rm -f "$keyfile"' EXIT
if [[ -n "$existing_keystore_b64" ]]; then
  echo "Reusing the existing oie-keystore Secret (never regenerated once it holds a key)."
  printf '%s' "$existing_keystore_b64" | base64 -d > "$keyfile"
elif [[ -n "${OIE_KEYSTORE_B64:-}" ]]; then
  echo "Using the keystore supplied via OIE_KEYSTORE_B64."
  printf '%s' "$OIE_KEYSTORE_B64" | base64 -d > "$keyfile"
else
  echo "No keystore supplied and none in the cluster -- generating a fresh keystore.jks with keytool."
  # Matches the engine's own self-signed keystore: a single RSA keypair under the
  # default alias. The engine adds its data-encryption secret key to this same
  # store on first boot; keeping the file stable is what preserves that key.
  #
  # keytool refuses to write into an existing empty file ("Keystore file exists,
  # but is empty"), and mktemp above already created $keyfile -- so remove it and
  # let keytool create the keystore itself. Errors are NOT swallowed: a keytool
  # failure must be visible in the job log (the JKS-format advisory it prints to
  # stderr is expected and harmless -- the engine ships JKS).
  rm -f "$keyfile"
  keytool -genkeypair -alias mirth -keyalg RSA -keysize 2048 -validity 3650 \
    -dname "CN=oie, OU=oie, O=oie, L=local, ST=local, C=AU" \
    -keystore "$keyfile" -storetype JKS \
    -storepass "$keystore_password" -keypass "$keystore_password"
fi
kubectl -n "$ns" create secret generic oie-keystore \
  --from-file="keystore.jks=$keyfile" \
  --dry-run=client -o yaml | kubectl apply -f -

# --- Resolve admin + database passwords ----------------------------------------
admin_password="${existing_admin_password:-${OIE_ADMIN_PASSWORD:-$(gen_password)}}"
if [[ -z "$existing_admin_password" && -z "${OIE_ADMIN_PASSWORD:-}" ]]; then
  echo "No OIE_ADMIN_PASSWORD supplied and none in the cluster -- generated one:"
  echo "    OIE_ADMIN_PASSWORD=$admin_password"
  echo "  (shown once; it is stored in the oie-secrets Secret from here on)."
fi

# The database password. When RDS is used its master password is authoritative;
# otherwise the in-cluster Postgres uses whatever is (or was) in the Secret.
if [[ -n "${RDS_MASTER_PASSWORD:-}" ]]; then
  db_password="$RDS_MASTER_PASSWORD"
else
  db_password="${existing_db_password:-$(gen_password)}"
fi

kubectl -n "$ns" create secret generic oie-secrets \
  --from-literal="POSTGRES_PASSWORD=$db_password" \
  --from-literal="DATABASE_PASSWORD=$db_password" \
  --from-literal="OIE_ADMIN_PASSWORD=$admin_password" \
  --from-literal="KEYSTORE_PASSWORD=$keystore_password" \
  --dry-run=client -o yaml | kubectl apply -f -

kubectl -n "$ns" create configmap oie-scripts \
  --from-file=scripts/oie-api.sh --from-file=scripts/oie-bootstrap-admin.sh \
  --dry-run=client -o yaml | kubectl apply -f -

# --- Config + database ---------------------------------------------------------
sed '/^---$/q' deploy/k8s/config.yaml | kubectl -n "$ns" apply -f -

if [[ -n "${DATABASE_URL:-}" && -n "${RDS_MASTER_USERNAME:-}" ]]; then
  # External database (the AWS/RDS path). Point the ConfigMap at it and do NOT
  # deploy the in-cluster Postgres.
  echo "Using the external database from DATABASE_URL / RDS_MASTER_USERNAME (RDS path)."
  kubectl -n "$ns" patch configmap oie-config --type merge \
    -p "{\"data\":{\"DATABASE_URL\":\"$DATABASE_URL\",\"DATABASE_USERNAME\":\"$RDS_MASTER_USERNAME\"}}"
else
  # Default local path: the in-cluster Postgres. config.yaml already points
  # DATABASE_URL/DATABASE_USERNAME at oie-db, so nothing to patch -- just deploy it.
  echo "Deploying the in-cluster Postgres (deploy/k8s/postgres.yaml); DATABASE_URL stays at oie-db."
  kubectl -n "$ns" apply -f deploy/k8s/postgres.yaml
  kubectl -n "$ns" rollout status statefulset/oie-db --timeout=5m
fi

# --- Engine --------------------------------------------------------------------
kubectl -n "$ns" apply -f deploy/k8s/engine.yaml -f deploy/k8s/services.yaml
kubectl -n "$ns" scale statefulset/oie-worker --replicas=0
kubectl -n "$ns" scale statefulset/oie-utility --replicas=1
kubectl -n "$ns" set image statefulset/oie-utility "engine=$IMAGE_TAG"
kubectl -n "$ns" patch statefulset oie-utility --type merge -p '{"spec":{"template":{"spec":{"imagePullSecrets":[{"name":"gitlab-registry"}]}}}}'
kubectl -n "$ns" patch service oie-admin --type merge -p '{"spec":{"type":"LoadBalancer","selector":{"app.kubernetes.io/name":"oie","app.kubernetes.io/component":"utility"}}}'
kubectl -n "$ns" patch service oie-channels --type merge -p '{"spec":{"type":"LoadBalancer","selector":{"app.kubernetes.io/name":"oie","app.kubernetes.io/component":"utility"}}}'
kubectl -n "$ns" delete poddisruptionbudget oie-worker --ignore-not-found
kubectl -n "$ns" rollout status statefulset/oie-utility --timeout=10m
