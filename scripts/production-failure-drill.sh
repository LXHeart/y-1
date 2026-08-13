#!/usr/bin/env bash
set -Eeuo pipefail

SCENARIO=""
DRILL_ID=""
OUTPUT=""

usage() {
  cat <<'EOF'
Usage: scripts/production-failure-drill.sh plan --scenario NAME --drill-id ID [--output PATH]

Supported scenarios: kafka-unavailable, temporal-unavailable, minio-unavailable,
video-provider-unavailable, readiness-failure.
This command is deliberately dry-run only: it writes a reviewable drill plan and never mutates
production. The operator supplies provider-specific fault injection and recovery commands in the
approved maintenance ticket after confirming scope with the infrastructure owner.
EOF
}

die() { echo "ERROR: $*" >&2; exit 1; }
[[ "${1:-}" == plan ]] || { usage >&2; exit 2; }
shift
while [[ $# -gt 0 ]]; do
  case "$1" in
    --scenario) SCENARIO="${2:?missing scenario}"; shift 2 ;;
    --drill-id) DRILL_ID="${2:?missing drill id}"; shift 2 ;;
    --output) OUTPUT="${2:?missing output path}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) die "unknown argument: $1" ;;
  esac
done

[[ "$DRILL_ID" =~ ^[A-Za-z0-9._-]+$ ]] || die "--drill-id must use letters, digits, dot, underscore, or hyphen"
case "$SCENARIO" in
  kafka-unavailable)
    dependency="external Kafka"
    injection="Block application-client connectivity to every configured Kafka bootstrap endpoint."
    observation="Confirm producer failures/retries, consumer lag or disconnect alerts, outbox backlog growth, and unaffected synchronous reads."
    recovery="Restore client connectivity, confirm consumers rejoin, replay DLT only after cause review, and wait for outbox/lag to return to baseline."
    abort="Abort if synchronous request error rate breaches the approved threshold or finance event reconciliation loses monotonic progress."
    ;;
  temporal-unavailable)
    dependency="external Temporal"
    injection="Block application-client connectivity to the production Temporal frontend without stopping application containers."
    observation="Confirm workflow start failures are visible, existing durable workflow state is retained, and stuck-workflow alerts fire."
    recovery="Restore mTLS connectivity, confirm workers poll the expected namespace/task queues, and verify pending workflows resume idempotently."
    abort="Abort if workflow history cannot be queried after recovery or any activity requires non-idempotent manual replay."
    ;;
  minio-unavailable)
    dependency="production object storage"
    injection="Deny the application service account access to the media bucket while preserving administrator recovery access."
    observation="Confirm upload/download failures are fail-closed, metadata is not marked complete, and storage/error alerts fire."
    recovery="Restore the service-account policy, retry only resumable operations, and reconcile media_reference rows with object existence."
    abort="Abort if completed metadata points to a missing object or any provider URL is exposed as a storage fallback."
    ;;
  video-provider-unavailable)
    dependency="configured production video provider"
    injection="Block only the Intelligence service account from the configured video provider origin; keep webhook ingress and object storage observable."
    observation="Confirm jobs remain queued/processing until bounded retry exhaustion, provider_timeout failures release budget and enqueue credit compensation, alerts identify provider errors, and no provider URL reaches clients."
    recovery="Restore provider connectivity, verify new jobs use the same frozen provider/pricing version, reconcile ai_run against video_generation_job actual cents/seconds, and retry only non-terminal jobs."
    abort="Abort if traffic falls back to Sandbox, any terminal job is overwritten by a late callback, compensation remains unclaimed past SLA, or credits/cents reconciliation diverges."
    ;;
  readiness-failure)
    dependency="one non-finance application instance"
    injection="Make one selected instance readiness probe fail while leaving its process observable; do not target all replicas."
    observation="Confirm the load balancer removes the instance, public health remains within threshold, and deployment readiness gate blocks promotion."
    recovery="Remove the fault, wait for readiness healthy, confirm traffic restoration, then exercise the documented image rollback dry-run."
    abort="Abort if healthy capacity falls below the approved minimum or public health fails."
    ;;
  *) die "unsupported --scenario: $SCENARIO" ;;
esac

generated_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
plan="$(cat <<EOF
# Grassland production failure drill plan
drill_id=$DRILL_ID
scenario=$SCENARIO
dependency=$dependency
generated_at=$generated_at
mode=dry-run

Preconditions:
- Approved maintenance ticket, named incident commander, infrastructure owner, finance approver, and rollback owner.
- Current PostgreSQL/MinIO backup manifest verified and linked to the ticket.
- Production Compose/secret preflight passes; dashboards and alert receiver are observable.
- Blast radius, steady-state metrics, abort thresholds, and provider-specific commands are reviewed before injection.

Injection:
- $injection

Observe:
- $observation

Recover:
- $recovery

Abort condition:
- $abort

Evidence required:
- Start/end timestamps, exact target and command, alert delivery timestamps, screenshots/query exports, backlog/lag before and after, public smoke result, measured RTO, measured RPO, and follow-up owner/deadline.

No fault was injected by this command.
EOF
)"

if [[ -n "$OUTPUT" ]]; then
  [[ "$OUTPUT" != / && "$OUTPUT" != */ ]] || die "--output must be a file path"
  mkdir -p "$(dirname "$OUTPUT")"
  printf '%s\n' "$plan" > "$OUTPUT"
  printf 'dry-run plan written: %s\n' "$OUTPUT"
else
  printf '%s\n' "$plan"
fi
