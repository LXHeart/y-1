#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUTPUT_DIR="${IMAGE_SECURITY_OUTPUT_DIR:-$ROOT_DIR/test-artifacts/image-security}"
TAG_PREFIX="${IMAGE_SECURITY_TAG_PREFIX:-grassland-ci}"
TRIVY_IMAGE="${TRIVY_IMAGE:-aquasec/trivy:0.69.3}"
IMAGE_SECURITY_PULL="${IMAGE_SECURITY_PULL:-true}"
TRIVY_CACHE_VOLUME="${TRIVY_CACHE_VOLUME:-grassland-trivy-cache}"

command -v docker >/dev/null 2>&1 || { echo "docker is required" >&2; exit 1; }
docker info >/dev/null 2>&1 || { echo "Docker daemon is unavailable" >&2; exit 1; }
docker buildx version >/dev/null 2>&1 || { echo "Docker Buildx is required" >&2; exit 1; }
[[ "$IMAGE_SECURITY_PULL" == true || "$IMAGE_SECURITY_PULL" == false ]] \
  || { echo "IMAGE_SECURITY_PULL must be true or false" >&2; exit 1; }
mkdir -p "$OUTPUT_DIR"

SERVICES=(frontend database-bootstrap edge-bff identity-service marketplace-service finance-service trust-service intelligence-service)

build_image() {
  local service="$1" context dockerfile
  if [[ "$service" == frontend ]]; then
    context="$ROOT_DIR"
    dockerfile="$ROOT_DIR/Dockerfile.frontend"
  else
    context="$ROOT_DIR/platform-java"
    dockerfile="$ROOT_DIR/platform-java/services/$service/Dockerfile"
  fi
  echo "[image-security] building $service"
  if [[ "$IMAGE_SECURITY_PULL" == true ]]; then
    docker build --pull --tag "$TAG_PREFIX/$service:scan" --file "$dockerfile" "$context"
  else
    docker build --tag "$TAG_PREFIX/$service:scan" --file "$dockerfile" "$context"
  fi
}

scan_image() {
  local service="$1" reference="$TAG_PREFIX/$service:scan"
  echo "[image-security] scanning $service for HIGH/CRITICAL vulnerabilities"
  docker run --rm \
    -v /var/run/docker.sock:/var/run/docker.sock \
    -v "$OUTPUT_DIR:/reports" \
    -v "$TRIVY_CACHE_VOLUME:/root/.cache/trivy" \
    "$TRIVY_IMAGE" image --docker-host unix:///var/run/docker.sock \
    --cache-dir /root/.cache/trivy \
    --scanners vuln --severity HIGH,CRITICAL --exit-code 1 \
    --format json --output "/reports/$service.vulnerabilities.json" "$reference"

  echo "[image-security] generating SPDX JSON SBOM for $service"
  docker run --rm \
    -v /var/run/docker.sock:/var/run/docker.sock \
    -v "$OUTPUT_DIR:/reports" \
    -v "$TRIVY_CACHE_VOLUME:/root/.cache/trivy" \
    "$TRIVY_IMAGE" image --docker-host unix:///var/run/docker.sock \
    --cache-dir /root/.cache/trivy \
    --scanners vuln --format spdx-json --output "/reports/$service.spdx.json" "$reference"
}

for service in "${SERVICES[@]}"; do
  build_image "$service"
  scan_image "$service"
done

{
  printf '%s\n' "Image signing is intentionally deferred until immutable registry digests exist."
  printf 'base_image_pull=%s\n' "$IMAGE_SECURITY_PULL"
} > "$OUTPUT_DIR/signing-status.txt"
echo "[image-security] all images passed; reports written to $OUTPUT_DIR"
