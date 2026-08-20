#!/usr/bin/env bash
set -Eeuo pipefail

# 观测栈运行时冒烟（第八批工程项）：起真实 Loki/Tempo/Promtail/Grafana 容器并证明
#   1) 四服务健康；
#   2) Tempo 真收 trace（宿主直发 OTLP/HTTP → Tempo 查询 API 读回同一 trace/span）；
#   3) Promtail 真收容器日志（打一次 nginx /health → Loki 查询 API 读回该容器新日志）；
#   4) Grafana 数据源已 provision（Loki/Tempo 可查）。
#
# 前提：默认 dev 栈（compose project=y-1）在跑——Promtail 的 relabel 只收本项目容器。
# 本脚本不改动基座 compose：用临时 override 发布 loki/tempo/grafana 端口到 127.0.0.1，
# 结束只移除 observability 服务（绝不 down 整个项目，避免误伤 dev 栈）。

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROJECT="${OBS_SMOKE_COMPOSE_PROJECT:-y-1}"
KEEP="false"

usage() {
  cat <<'EOF'
Usage: scripts/local-observability-smoke.sh [--keep]

Runtime smoke for the local observability stack (profile=observability on the running
dev compose project). Requires the dev stack (project y-1) to be up. Adds temporary
localhost port mappings via a generated compose override; removes only the observability
services on exit unless --keep.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --keep) KEEP="true"; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
done

command -v docker >/dev/null 2>&1 || { echo "docker is required" >&2; exit 1; }
command -v curl >/dev/null 2>&1 || { echo "curl is required" >&2; exit 1; }

OVERRIDE="$(mktemp "${TMPDIR:-/tmp}/observability-smoke-override.XXXXXX.yml")"
cat >"$OVERRIDE" <<'YAML'
services:
  loki:
    # 冒烟专用：tmpfs 存储（每次运行全新索引）+ 发布端口。
    # 持久 loki_data 卷会保留旧流状态——重建容器的回放批撞上「落后流内最新 ~1h」
    # 的流内乱序防线被整批 400，与 promtail 共享批的新鲜日志一并丢弃（见 loki.yml 注释）。
    # 全新索引无旧流可撞，回放批整体可收。
    volumes: !override
      - ./platform-java/deploy/observability/loki/loki.yml:/etc/loki/loki.yml:ro
    tmpfs:
      - /loki:uid=10001,gid=10001,mode=0770
    ports:
      - "127.0.0.1:13100:3100"
  tempo:
    ports:
      - "127.0.0.1:13200:3200"
      - "127.0.0.1:14318:4318"
  grafana:
    ports:
      - "127.0.0.1:13000:3000"
YAML

compose=(docker compose --project-name "$PROJECT" --project-directory "$ROOT_DIR"
  -f "$ROOT_DIR/docker-compose.yml" -f "$OVERRIDE")
OBS_SERVICES=(loki tempo promtail grafana prometheus)

cleanup() {
  rm -f "$OVERRIDE"
  if [[ "$KEEP" != true ]]; then
    "${compose[@]}" rm -sf "${OBS_SERVICES[@]}" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

if ! "${compose[@]}" ps --status running --format json 2>/dev/null \
    | grep -q '"Name":"'"${PROJECT}"'-frontend'; then
  echo "dev stack (project ${PROJECT}) 不在运行——本冒烟需要它在跑（Promtail 只收本项目容器日志）" >&2
  exit 1
fi

"${compose[@]}" --profile observability up -d "${OBS_SERVICES[@]}" >/dev/null

wait_ready() {
  local name="$1" url="$2" attempts="${3:-60}"
  for _ in $(seq 1 "$attempts"); do
    if curl --fail --silent --show-error --max-time 3 "$url" >/dev/null 2>&1; then
      return 0
    fi
    sleep 2
  done
  echo "$name did not become ready at $url" >&2
  "${compose[@]}" logs --no-color --tail=50 loki tempo promtail grafana prometheus >&2 || true
  return 1
}

wait_ready loki http://127.0.0.1:13100/ready
wait_ready tempo http://127.0.0.1:13200/ready
wait_ready grafana http://127.0.0.1:13000/api/health
# up -d 重建期 loki 有 DNS 空窗，promtail 可能已陷入长退避——就绪后重启一次让它干净起跑。
"${compose[@]}" restart promtail >/dev/null 2>&1
echo "observability services healthy: loki tempo grafana (+promtail)"

# --- 1) Tempo：宿主直发一条 OTLP/HTTP JSON span，读回同一 trace/span ---
trace_id="$(openssl rand -hex 16)"
span_id="$(openssl rand -hex 8)"
started="$(date +%s)000000000"
ended="$((started + 1000000))"
payload="$(printf '{"resourceSpans":[{"resource":{"attributes":[{"key":"service.name","value":{"stringValue":"grassland-obs-smoke"}}]},"scopeSpans":[{"scope":{"name":"grassland.obs.smoke"},"spans":[{"traceId":"%s","spanId":"%s","name":"observability-runtime-smoke","kind":1,"startTimeUnixNano":"%s","endTimeUnixNano":"%s","status":{"code":1}}]}]}]}' \
  "$trace_id" "$span_id" "$started" "$ended")"
curl --fail --silent --show-error -H 'Content-Type: application/json' \
  --data "$payload" http://127.0.0.1:14318/v1/traces >/dev/null

trace_found="false"
for _ in $(seq 1 45); do
  # Tempo 查询默认 messagepack；JSON 模式的 traceId/spanId 是 base64——按 span 名断言最稳
  body="$(curl --silent --show-error --max-time 5 -H 'Accept: application/json' \
    "http://127.0.0.1:13200/api/traces/${trace_id}" || true)"
  if grep -q 'observability-runtime-smoke' <<<"$body" \
      && grep -q 'grassland-obs-smoke' <<<"$body"; then trace_found="true"; break; fi
  sleep 2
done
if [[ "$trace_found" != true ]]; then
  echo "tempo did not return the smoke span (trace_id=${trace_id})" >&2
  "${compose[@]}" logs --no-color --tail=50 tempo >&2 || true
  exit 1
fi
echo "tempo trace round-trip passed (trace_id=${trace_id})"

# --- 2) Promtail → Loki：内容级断言（label 无关） ---
# tempo 刚由本脚本创建（秒级历史，回放不会被流内乱序防线拒绝），且上一步的 trace 查询
# 会让它打出含 traceId 的 info 日志。⚠️ 刻意不按 service_name 检索：Loki 3.5 对 promtail
# 的 protobuf 推送做 OTLP 标签规范化，自定义标签（service_name/container_name）会被移入
# structured metadata、流标签只剩服务端补的 service_name=unknown_service（JSON 直推路径
# 不受影响——已实测）。断言内容命中即证明 docker 发现→读取→管线→推送→落库→查询全链。
logs_found="false"
for _ in $(seq 1 60); do
  query_result="$(curl --silent --show-error --max-time 5 \
    --data-urlencode 'query={service_name=~".+"} |= "'"$trace_id"'"' \
    --data-urlencode 'limit=5' \
    "http://127.0.0.1:13100/loki/api/v1/query_range" || true)"
  if grep -q '"status":"success"' <<<"$query_result" && grep -q '"values"' <<<"$query_result" \
      && grep -q "$trace_id" <<<"$query_result"; then
    logs_found="true"; break
  fi
  sleep 2
done
if [[ "$logs_found" != true ]]; then
  echo "loki did not return the tempo log line (trace_id=${trace_id}) via promtail" >&2
  "${compose[@]}" logs --no-color --tail=50 promtail loki >&2 || true
  exit 1
fi
echo "promtail -> loki log pipeline passed (tempo log line with trace_id found)"

# --- 3) Grafana：admin 凭据读 datasource 列表，确认 loki/tempo 已 provision ---
admin_password="$(tr -d '\n' < "$ROOT_DIR/platform-java/deploy/observability/grafana/admin-password.example")"
datasources="$(curl --fail --silent --show-error --max-time 5 \
  -u "admin:${admin_password}" http://127.0.0.1:13000/api/datasources)"
for expected_ds in loki tempo; do
  if ! grep -q "\"type\":\"${expected_ds}\"" <<<"$datasources"; then
    echo "grafana is missing the provisioned ${expected_ds} datasource" >&2
    exit 1
  fi
done
echo "grafana datasources provisioned: loki tempo"

echo "observability runtime smoke passed"
