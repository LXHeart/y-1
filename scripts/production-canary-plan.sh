#!/usr/bin/env bash
set -Eeuo pipefail

RELEASE_ID=""
OUTPUT=""
PUBLIC_HEALTH_URL="${PUBLIC_HEALTH_URL:-}"
PROMETHEUS_URL="${PROMETHEUS_URL:-}"
CANARY_SERVICE="${CANARY_SERVICE:-edge-bff}"
CANARY_IMAGE="${CANARY_IMAGE:-}"
BASELINE_IMAGE="${BASELINE_IMAGE:-}"

usage() {
  cat <<'EOF'
Usage: scripts/production-canary-plan.sh plan --release-id ID --canary-image DIGEST \
  --baseline-image DIGEST --public-health-url URL --prometheus-url URL [--service NAME] [--output PATH]

Generates a reviewable canary rollout plan. It never changes traffic, containers, or routing.
The deployment operator must provide the actual ingress/controller commands and record measured
Prometheus results before moving between phases.
EOF
}

die() { echo "ERROR: $*" >&2; exit 1; }
[[ "${1:-}" == plan ]] || { usage >&2; exit 2; }
shift
while [[ $# -gt 0 ]]; do
  case "$1" in
    --release-id) RELEASE_ID="${2:?missing release id}"; shift 2 ;;
    --canary-image) CANARY_IMAGE="${2:?missing canary image}"; shift 2 ;;
    --baseline-image) BASELINE_IMAGE="${2:?missing baseline image}"; shift 2 ;;
    --public-health-url) PUBLIC_HEALTH_URL="${2:?missing health URL}"; shift 2 ;;
    --prometheus-url) PROMETHEUS_URL="${2:?missing Prometheus URL}"; shift 2 ;;
    --service) CANARY_SERVICE="${2:?missing service}"; shift 2 ;;
    --output) OUTPUT="${2:?missing output path}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) die "unknown argument: $1" ;;
  esac
done

[[ "$RELEASE_ID" =~ ^[A-Za-z0-9._-]+$ ]] || die "--release-id is invalid"
[[ "$CANARY_IMAGE" == *@sha256:* ]] || die "--canary-image must be pinned by digest"
[[ "$BASELINE_IMAGE" == *@sha256:* ]] || die "--baseline-image must be pinned by digest"
[[ "$PUBLIC_HEALTH_URL" == https://* ]] || die "--public-health-url must use https"
[[ "$PROMETHEUS_URL" == https://* ]] || die "--prometheus-url must use https"
[[ "$CANARY_SERVICE" =~ ^[a-z0-9][a-z0-9-]*$ ]] || die "--service is invalid"

generated_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
plan="$(cat <<EOF
# Grassland production canary rollout plan
release_id=$RELEASE_ID
service=$CANARY_SERVICE
canary_image=$CANARY_IMAGE
baseline_image=$BASELINE_IMAGE
public_health_url=$PUBLIC_HEALTH_URL
prometheus_url=$PROMETHEUS_URL
generated_at=$generated_at
mode=dry-run

Preconditions:
- Production preflight, image signature/SPDX attestation verification, backup manifest verification, and readiness checks passed.
- Named incident commander, rollback owner, finance approver, and ingress/operator owner are present.
- Baseline has at least 15 minutes of stable 5xx, latency, traffic, JVM, outbox age, and consumer lag observations.
- The canary image is deployed alongside the baseline without changing database migration compatibility.

Phases:
1. Warm-up: route 0% user traffic; verify canary readiness, local smoke, logs, metrics, and dependency connections for 5 minutes.
2. Canary: route 1% of traffic for at least 10 minutes; compare canary against baseline.
3. Expand: route 10%, then 25%, then 50%, holding each step for at least 10 minutes and recording evidence.
4. Promote: route 100% only after the incident commander approves every hold point; observe for at least 10 minutes and retain the baseline digest for rollback.

Abort conditions at every hold point:
- HTTP 5xx ratio > 5% for 2 consecutive minutes or > 2 percentage points above baseline.
- p95 latency > 2 seconds for 2 consecutive minutes or > 50% above baseline.
- Readiness failure, dependency error-rate spike, outbox age/consumer lag above the approved SLO, or any security alert.
- Any financial reconciliation, idempotency, or data-integrity anomaly.

Required Prometheus evidence:
- Query and export request 5xx ratio and p95 latency for canary and baseline.
- Export JVM heap/CPU, readiness, outbox age, consumer lag, and dependency error metrics.
- Record query timestamps, dashboard links, alert delivery timestamps, and operator approvals.

Abort and rollback:
- Stop traffic expansion immediately and preserve logs/metric snapshots.
- Route traffic to baseline image $BASELINE_IMAGE using the approved ingress command.
- Run production-smoke.sh --base-url derived from $PUBLIC_HEALTH_URL, then wait for readiness and repeat the metric window.
- Open an incident if rollback health or reconciliation checks fail; do not reverse database migrations automatically.

No traffic was changed by this command.
EOF
)"

if [[ -n "$OUTPUT" ]]; then
  [[ "$OUTPUT" != / && "$OUTPUT" != */ ]] || die "--output must be a file path"
  mkdir -p "$(dirname "$OUTPUT")"
  printf '%s\n' "$plan" > "$OUTPUT"
  printf 'dry-run canary plan written: %s\n' "$OUTPUT"
else
  printf '%s\n' "$plan"
fi
