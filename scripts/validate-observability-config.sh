#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OBS_DIR="$ROOT_DIR/platform-java/deploy/observability"
PROMETHEUS="$OBS_DIR/prometheus/prometheus.yml"
PLATFORM_RULES="$OBS_DIR/prometheus/rules/platform-services.yml"
MESSAGING_RULES="$OBS_DIR/prometheus/rules/platform-messaging.yml"
ALERTMANAGER="$OBS_DIR/alertmanager/alertmanager.yml"
DASHBOARD="$OBS_DIR/grafana/dashboards/platform-overview.json"
JAVA_SERVICES_DIR="$ROOT_DIR/platform-java/services"
COLLECTOR_CONFIG="$OBS_DIR/otel-collector/config.yaml"

fail() { echo "ERROR: $*" >&2; exit 1; }
require_text() {
  local file="$1" text="$2" description="$3"
  grep -Fq "$text" "$file" || fail "$description"
}

command -v jq >/dev/null 2>&1 || fail "jq is required"
for file in "$PROMETHEUS" "$PLATFORM_RULES" "$MESSAGING_RULES" "$ALERTMANAGER" "$DASHBOARD" "$COLLECTOR_CONFIG"; do
  [[ -r "$file" ]] || fail "observability config is not readable: $file"
done
require_text "$COLLECTOR_CONFIG" 'receivers:' "OTLP collector must define receivers"
require_text "$COLLECTOR_CONFIG" 'exporters: [debug]' "local OTLP collector must retain a non-network debug exporter"
require_text "$COLLECTOR_CONFIG" 'health_check:' "local OTLP collector must expose a health check"
require_text "$ROOT_DIR/scripts/local-otel-trace-smoke.sh" '/v1/traces' "local OTLP smoke must send traces through the HTTP receiver"
require_text "$ROOT_DIR/scripts/local-otel-trace-smoke.sh" 'Trace ID' "local OTLP smoke must verify collector delivery logs"

for service in edge-bff identity-service marketplace-service finance-service trust-service intelligence-service; do
  require_text "$PROMETHEUS" "job_name: $service" "Prometheus must scrape $service"
  build_file="$JAVA_SERVICES_DIR/$service/build.gradle.kts"
  application_file="$JAVA_SERVICES_DIR/$service/src/main/resources/application.yml"
  require_text "$build_file" 'implementation(libs.spring.boot.opentelemetry)' "$service must include the Boot OpenTelemetry starter"
  require_text "$application_file" $'  otlp:\n    metrics:\n      export:\n        enabled: false' "$service must disable the default OTLP metrics exporter; Prometheus owns metrics export"
  require_text "$application_file" 'enabled: ${OTEL_TRACING_ENABLED:false}' "$service tracing export must be environment-controlled and default off"
  require_text "$application_file" 'enabled: ${OTEL_EXPORT_ENABLED:false}' "$service OTLP export must be environment-controlled and default off"
  require_text "$application_file" 'probability: ${OTEL_TRACING_SAMPLING_PROBABILITY:0.1}' "$service tracing sampling must be environment-controlled"
  require_text "$application_file" 'consume: W3C' "$service must consume W3C trace context"
  require_text "$application_file" 'produce: W3C' "$service must produce W3C trace context"
  require_text "$application_file" 'endpoint: ${OTEL_EXPORTER_OTLP_TRACES_ENDPOINT:}' "$service OTLP endpoint must be environment-controlled without a local default"
done
for service in identity-service marketplace-service finance-service trust-service intelligence-service; do
  application_file="$JAVA_SERVICES_DIR/$service/src/main/resources/application.yml"
  require_text "$application_file" $'    template:\n      observation-enabled: true' "$service Kafka producer observation must be enabled"
done
for service in identity-service marketplace-service; do
  application_file="$JAVA_SERVICES_DIR/$service/src/main/resources/application.yml"
  require_text "$application_file" $'    listener:\n      observation-enabled: true' "$service Kafka consumer observation must be enabled"
done
require_text "$JAVA_SERVICES_DIR/identity-service/src/main/java/com/grassland/identity/notification/NotificationKafkaReliabilityConfig.java" \
  'setObservationEnabled(true)' "identity custom Kafka listener factory must preserve observation"
require_text "$JAVA_SERVICES_DIR/marketplace-service/src/main/java/com/grassland/marketplace/config/KafkaConsumerReliabilityConfig.java" \
  'setObservationEnabled(true)' "marketplace custom Kafka listener factory must preserve observation"
require_text "$JAVA_SERVICES_DIR/marketplace-service/build.gradle.kts" \
  'implementation(libs.temporal.opentracing)' "marketplace must include the Temporal OpenTracing bridge"
require_text "$JAVA_SERVICES_DIR/trust-service/build.gradle.kts" \
  'implementation(libs.temporal.opentracing)' "trust must include the Temporal OpenTracing bridge"
require_text "$JAVA_SERVICES_DIR/marketplace-service/src/main/java/com/grassland/marketplace/config/TemporalTracingConfig.java" \
  'new OpenTracingClientInterceptor(options)' "marketplace must register Temporal client tracing"
require_text "$JAVA_SERVICES_DIR/marketplace-service/src/main/java/com/grassland/marketplace/config/TemporalTracingConfig.java" \
  'new OpenTracingWorkerInterceptor(options)' "marketplace must register Temporal worker tracing"
require_text "$JAVA_SERVICES_DIR/trust-service/src/main/java/com/grassland/trust/config/TemporalTracingConfig.java" \
  'new OpenTracingClientInterceptor(options)' "trust must register Temporal client tracing"
require_text "$JAVA_SERVICES_DIR/trust-service/src/main/java/com/grassland/trust/config/TemporalTracingConfig.java" \
  'new OpenTracingWorkerInterceptor(options)' "trust must register Temporal worker tracing"
require_text "$ROOT_DIR/platform-java/gradle/libs.versions.toml" \
  'spring-boot-opentelemetry = { module = "org.springframework.boot:spring-boot-starter-opentelemetry" }' \
  "version catalog must expose the Boot-managed OpenTelemetry starter"
require_text "$PROMETHEUS" '/etc/prometheus/rules/*.yml' "Prometheus must load checked-in alert rules"
require_text "$PROMETHEUS" 'alertmanager:9093' "Prometheus must route alerts to Alertmanager"

for alert in GrasslandServiceDown GrasslandHighHttp5xxRate GrasslandJvmHeapPressure GrasslandProcessCpuPressure; do
  require_text "$PLATFORM_RULES" "alert: $alert" "missing platform alert: $alert"
done
for alert in GrasslandOutboxBacklogOldestAge GrasslandOutboxMetricsMissing GrasslandKafkaConsumerLagHigh GrasslandKafkaConsumerLagMetricsMissing; do
  require_text "$MESSAGING_RULES" "alert: $alert" "missing messaging alert: $alert"
done
require_text "$MESSAGING_RULES" 'grassland_outbox_oldest_pending_age_seconds' "outbox age alert must use the exported Micrometer gauge"
require_text "$MESSAGING_RULES" 'kafka_consumer_records_lag_max' "consumer lag alert must use the exported Kafka metric"

require_text "$ALERTMANAGER" 'receiver: operations-webhook' "Alertmanager must route alerts to operations-webhook"
require_text "$ALERTMANAGER" 'url_file: /run/secrets/alertmanager_webhook_url' "Alertmanager receiver must use the mounted secret file"
require_text "$ALERTMANAGER" 'send_resolved: true' "Alertmanager must send resolved notifications"

jq -e '.panels | map(.title) | index("Outbox Oldest Pending Age") != null' "$DASHBOARD" >/dev/null \
  || fail "Grafana dashboard must show outbox age"
jq -e '.panels | map(.title) | index("Kafka Consumer Lag") != null' "$DASHBOARD" >/dev/null \
  || fail "Grafana dashboard must show Kafka consumer lag"

echo "observability configuration contract is valid"
