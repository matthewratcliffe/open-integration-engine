#!/usr/bin/env bash
set -euo pipefail
: "${KUBE_CONFIG_B64:?required}"
: "${OIE_KEYSTORE_B64:?required}"
: "${DATABASE_URL:?required}"
: "${DATABASE_USERNAME:?required}"
: "${POSTGRES_PASSWORD:?required}"
: "${OIE_ADMIN_PASSWORD:?required}"
: "${KEYSTORE_PASSWORD:?required}"
: "${IMAGE_TAG:?required}"

mkdir -p "$HOME/.kube"
printf '%s' "$KUBE_CONFIG_B64" | base64 -d > "$HOME/.kube/config"
chmod 600 "$HOME/.kube/config"
ns="${OIE_NAMESPACE:-oie-local}"
kubectl create namespace "$ns" --dry-run=client -o yaml | kubectl apply -f -
kubectl -n "$ns" create secret docker-registry gitlab-registry --docker-server="$CI_REGISTRY" --docker-username="$CI_REGISTRY_USER" --docker-password="$CI_REGISTRY_PASSWORD" --dry-run=client -o yaml | kubectl apply -f -
keyfile="$(mktemp)"; trap 'rm -f "$keyfile"' EXIT
printf '%s' "$OIE_KEYSTORE_B64" | base64 -d > "$keyfile"
kubectl -n "$ns" create secret generic oie-keystore --from-file="keystore.jks=$keyfile" --dry-run=client -o yaml | kubectl apply -f -
kubectl -n "$ns" create secret generic oie-secrets --from-literal="POSTGRES_PASSWORD=$POSTGRES_PASSWORD" --from-literal="DATABASE_PASSWORD=$POSTGRES_PASSWORD" --from-literal="OIE_ADMIN_PASSWORD=$OIE_ADMIN_PASSWORD" --from-literal="KEYSTORE_PASSWORD=$KEYSTORE_PASSWORD" --dry-run=client -o yaml | kubectl apply -f -
kubectl -n "$ns" create configmap oie-scripts --from-file=scripts/oie-api.sh --from-file=scripts/oie-bootstrap-admin.sh --dry-run=client -o yaml | kubectl apply -f -
sed '/^---$/q' deploy/k8s/config.yaml | kubectl -n "$ns" apply -f -
kubectl -n "$ns" patch configmap oie-config --type merge -p "{\"data\":{\"DATABASE_URL\":\"$DATABASE_URL\",\"DATABASE_USERNAME\":\"$DATABASE_USERNAME\"}}"
kubectl -n "$ns" apply -f deploy/k8s/engine.yaml -f deploy/k8s/services.yaml
kubectl -n "$ns" scale statefulset/oie-worker --replicas=0
kubectl -n "$ns" scale statefulset/oie-utility --replicas=1
kubectl -n "$ns" set image statefulset/oie-utility "engine=$IMAGE_TAG"
kubectl -n "$ns" patch statefulset oie-utility --type merge -p '{"spec":{"template":{"spec":{"imagePullSecrets":[{"name":"gitlab-registry"}]}}}}'
kubectl -n "$ns" patch service oie-admin --type merge -p '{"spec":{"type":"LoadBalancer","selector":{"app.kubernetes.io/name":"oie","app.kubernetes.io/component":"utility"}}}'
kubectl -n "$ns" patch service oie-channels --type merge -p '{"spec":{"type":"LoadBalancer","selector":{"app.kubernetes.io/name":"oie","app.kubernetes.io/component":"utility"}}}'
kubectl -n "$ns" delete poddisruptionbudget oie-worker --ignore-not-found
kubectl -n "$ns" rollout status statefulset/oie-utility --timeout=10m
