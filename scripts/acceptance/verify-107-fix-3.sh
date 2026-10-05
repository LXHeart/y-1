#!/usr/bin/env bash
# verify-107-fix-3.sh — 任务书 107-fix-3 分层验证唯一入口（W052；§12.3 V-01～V-15）。
#
# 用法（仓库根执行）：
#   bash scripts/acceptance/verify-107-fix-3.sh --stage <名称> --run auto
#   bash scripts/acceptance/verify-107-fix-3.sh --stage <名称> --run <已有runId>   # 续跑
#
# 契约（§12.3）：
#   - auto 生成唯一 runId；--run 已有标识续跑必须核验源码摘要（变化→exit2）；
#     重复 stage 保存 attempt-N 子目录而非覆盖。
#   - 未知参数/未知 stage/尚未交付测试 → exit 2 并写 NOT_RUN 证据，绝不打印全绿。
#   - stage 失败 exit 1；成功 exit 0 且原始报告校验（executed>0、failed/skip/todo=0、
#     必需 TC 全发现）全部满足。stdout 只保留 stage/runId/退出码/摘要行；
#     详细日志一律写 run 目录文件。
#   - 证据根：test-artifacts/task-107/fix3/runs/<runId>/<stage>/attempt-<N>/。
#
# 退出码：0 成功；1 stage 门禁失败；2 用法/未知/未交付 NOT_RUN/续跑摘要不符；
#         3 报告缺失或非法；130/143 信号中断（写 aborted.json，保留现场）。
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "${REPO_ROOT}"
ORIGINAL_ARGS=("$@")
source "$REPO_ROOT/scripts/lib/local-stack.sh"

SELF="$REPO_ROOT/scripts/acceptance/verify-107-fix-3.sh"
RESULTS_PARSER="scripts/acceptance/hypit-fix3-results.mjs"
FIX2_ENTRY="scripts/acceptance/verify-107-fix-2.sh"
TASK_VERSION="107-fix-3 v1.1.0"
RUNS_ROOT="${HYPIT_FIX3_RUNS_ROOT:-test-artifacts/task-107/fix3/runs}"
RUNS_ROOT_ABS="${RUNS_ROOT}"
[[ "${RUNS_ROOT_ABS}" = /* ]] || RUNS_ROOT_ABS="${REPO_ROOT}/${RUNS_ROOT}"

now() { date -u +%Y-%m-%dT%H:%M:%SZ; }

usage() {
  cat <<'USAGE'
verify-107-fix-3.sh — 107-fix-3 分层验证唯一入口（§12.3 V-01～V-15）
  --stage tooling|fixtures|variant|maintenance|engine|domain|evidence|vision|
          archive|context|e2e|live-contract|docs|regression|all
  --run auto|<runId>   auto=新 run；已有 runId=续跑（核验源码摘要）
  --help               本说明
USAGE
}

# ── 参数解析（未知 exit 2） ───────────────────────────────────────────────
STAGE=""
RUN_ARG=""
while [ $# -gt 0 ]; do
  case "$1" in
    --stage) STAGE="${2:-}"; shift 2 ;;
    --run) RUN_ARG="${2:-}"; shift 2 ;;
    --help|-h) usage; exit 0 ;;
    *) printf '未知参数: %s\n' "$1" >&2; usage >&2; exit 2 ;;
  esac
done
[ -n "${STAGE}" ] || { printf '缺少 --stage\n' >&2; usage >&2; exit 2; }
[ -n "${RUN_ARG}" ] || { printf '缺少 --run（auto 或已有 runId）\n' >&2; usage >&2; exit 2; }

case "${STAGE}" in
  tooling|fixtures|variant|maintenance|engine|domain|evidence|vision|archive|context|e2e|live-contract|docs|regression|all) ;;
  *) printf '未知 stage: %s\n' "${STAGE}" >&2; usage >&2; exit 2 ;;
esac

# ── run 目录与 manifest（续跑核验源码摘要；重复 stage 走 attempt-N） ─────────
# runId 优先级：显式 --run <id> > 守卫重入继承的 HYPIT_FIX3_RUN_ID > auto 新生成。
# （显式 id 必须优先，防止嵌套/自测调用继承外层 env 后写错 run 目录。）
if [ "${RUN_ARG}" != "auto" ]; then
  if [[ "${RUN_ARG}" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$ ]]; then
    RUN_ID="${RUN_ARG}"
  else
    printf '非法 runId: %s\n' "${RUN_ARG}" >&2
    exit 2
  fi
elif [ -n "${HYPIT_FIX3_RUN_ID:-}" ] && [[ "${HYPIT_FIX3_RUN_ID}" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$ ]]; then
  RUN_ID="${HYPIT_FIX3_RUN_ID}"
else
  RUN_ID="fix3-$(date -u +%Y%m%dT%H%M%SZ)-$(printf '%04x' $((RANDOM % 65536)))"
fi
export HYPIT_FIX3_RUN_ID="${RUN_ID}"
RUN_DIR="${RUNS_ROOT_ABS}/${RUN_ID}"
RUN_MANIFEST="${RUN_DIR}/manifest.json"
mkdir -p "${RUN_DIR}"

if [ -f "${RUN_MANIFEST}" ]; then
  # 续跑：源码摘要必须一致（防止旧 run 混入新源码证据）。
  if ! node "${RESULTS_PARSER}" check-continuation --manifest "${RUN_MANIFEST}"; then
    printf 'FAIL 续跑被拒绝（run=%s stage=%s）——用 --run auto 新开 run\n' "${RUN_ID}" "${STAGE}"
    exit 2
  fi
else
  node "${RESULTS_PARSER}" manifest --file "${RUN_MANIFEST}" --init --run-id "${RUN_ID}" >/dev/null || {
    printf 'FAIL manifest 初始化失败\n'; exit 1; }
fi

ATTEMPT=1
while [ -d "${RUN_DIR}/${STAGE}/attempt-${ATTEMPT}" ]; do ATTEMPT=$((ATTEMPT + 1)); done
STAGE_DIR="${RUN_DIR}/${STAGE}/attempt-${ATTEMPT}"
mkdir -p "${STAGE_DIR}"
export FIX3_STAGE_DIR="${STAGE_DIR}"

# ── 信号安全：杀本次子进程、写 aborted 痕迹，绝无声称成功 ──────────────────
CHILD_PID=""
on_signal() {
  code=$1
  if [ -n "${CHILD_PID}" ]; then
    kill -TERM "${CHILD_PID}" 2>/dev/null || true
    wait "${CHILD_PID}" 2>/dev/null || true
    CHILD_PID=""
  fi
  node "${RESULTS_PARSER}" manifest --file "${RUN_MANIFEST}" --stage "${STAGE}" \
    --attempt "${ATTEMPT}" --stage-dir "${STAGE_DIR}" --exit "${code}" >/dev/null 2>&1 || true
  printf '{"taskBook":"%s","stage":"%s","runId":"%s","attempt":%s,"exitCode":%s,"aborted":true,"finishedAt":"%s"}\n' \
    "${TASK_VERSION}" "${STAGE}" "${RUN_ID}" "${ATTEMPT}" "${code}" "$(now)" \
    >"${STAGE_DIR}/aborted.json" 2>/dev/null || true
  printf 'ABORTED stage=%s run=%s attempt=%s exit=%s（现场保留于 %s）\n' "${STAGE}" "${RUN_ID}" "${ATTEMPT}" "${code}" "${STAGE_DIR}"
  exit "${code}"
}
trap 'on_signal 130' INT
trap 'on_signal 143' TERM

# ── run/attempt 公共元数据 ────────────────────────────────────────────────
COMMIT="$(node "${RESULTS_PARSER}" identity --json-out "${STAGE_DIR}/identity.json" >/dev/null 2>&1 && node -e 'console.log(JSON.parse(require("node:fs").readFileSync(process.argv[1],"utf8")).commit)' "${STAGE_DIR}/identity.json" || echo unknown)"
DIGEST="$(node -e 'console.log(JSON.parse(require("node:fs").readFileSync(process.argv[1],"utf8")).sourceDigest)' "${STAGE_DIR}/identity.json" 2>/dev/null || echo unknown)"
STARTED_AT="$(now)"
ACC="${STAGE_DIR}/aggregate.json"
rm -f "${ACC}"

printf '[%s] stage=%s run=%s attempt=%s dir=%s\n' "${STARTED_AT}" "${STAGE}" "${RUN_ID}" "${ATTEMPT}" "${STAGE_DIR#"$REPO_ROOT"/}"

run_child() { # $1=log 其余=命令
  local log="$1"; shift
  "$@" >"${log}" 2>&1 &
  CHILD_PID=$!
  local code=0
  wait "${CHILD_PID}" || code=$?
  CHILD_PID=""
  return "${code}"
}
run_child_sh() { # $1=log $2=shell 片段（详细输出全进 log）
  local log="$1" snippet="$2"
  bash -c "${snippet}" >"${log}" 2>&1 &
  CHILD_PID=$!
  local code=0
  wait "${CHILD_PID}" || code=$?
  CHILD_PID=""
  return "${code}"
}

record_runner() { # $1=label $2=exit（无报告的子进程登记）
  node "${RESULTS_PARSER}" record --accumulate "${ACC}" --label "$1" --exit "$2" >/dev/null
}
record_problem() { # $1=label $2=问题
  node "${RESULTS_PARSER}" record --accumulate "${ACC}" --label "$1" --exit 1 \
    --problems "$2" >/dev/null
}

run_checked() { # $1=label $2=log 其余=命令：失败登记问题，成功登记 exit0
  local label="$1" log="$2"; shift 2
  local code=0
  run_child "$log" "$@" || code=$?
  if [ "${code}" -eq 0 ]; then record_runner "${label}" 0; else
    record_problem "${label}" "${label} 非零（log=${log#"$REPO_ROOT"/}）"
  fi
  return "${code}"
}
run_checked_sh() { # $1=label $2=log $3=shell 片段
  local label="$1" log="$2" snippet="$3"
  local code=0
  run_child_sh "$log" "$snippet" || code=$?
  if [ "${code}" -eq 0 ]; then record_runner "${label}" 0; else
    record_problem "${label}" "${label} 非零（log=${log#"$REPO_ROOT"/}）"
  fi
  return "${code}"
}

finish_stage() { # $1=期望TC（逗号分隔）
  local expect="$1"
  local code=0
  node "${RESULTS_PARSER}" emit --accumulate "${ACC}" --out "${STAGE_DIR}/results.json" \
    --stage "${STAGE}" --run-id "${RUN_ID}" --attempt "${ATTEMPT}" \
    --commit "${COMMIT}" --digest "${DIGEST}" --started "${STARTED_AT}" \
    --expect "${expect}" || code=$?
  node "${RESULTS_PARSER}" manifest --file "${RUN_MANIFEST}" --stage "${STAGE}" \
    --attempt "${ATTEMPT}" --stage-dir "${STAGE_DIR}" --exit "${code}" >/dev/null
  if [ "${code}" -ne 0 ]; then
    printf 'FAIL stage=%s run=%s attempt=%s exit=%s（详情 %s/results.json 与各 .log）\n' \
      "${STAGE}" "${RUN_ID}" "${ATTEMPT}" "${code}" "${STAGE_DIR#"$REPO_ROOT"/}"
  else
    printf 'PASS stage=%s run=%s attempt=%s（详情 %s/results.json）\n' \
      "${STAGE}" "${RUN_ID}" "${ATTEMPT}" "${STAGE_DIR#"$REPO_ROOT"/}"
  fi
  exit "${code}"
}

not_run() { # $1=原因
  local reason="$1"
  printf 'NOT_RUN stage=%s run=%s 原因=%s（未交付 stage 必须 exit 2）\n' "${STAGE}" "${RUN_ID}" "${reason}"
  node -e '
    const fs = require("node:fs");
    fs.writeFileSync(process.argv[1], JSON.stringify({
      taskBook: process.argv[2], stage: process.argv[3], runId: process.argv[4],
      attempt: Number(process.argv[5]), generator: "w53", exitCode: 2,
      commit: process.argv[6], startedAt: process.argv[7], finishedAt: new Date().toISOString(),
      notRun: [{ stage: process.argv[3], reason: process.argv[8] }], tests: null, tc: null,
      verdict: "NOT_RUN", live: "NOT_RUN", problems: [], notes: [],
    }, null, 2) + "\n");
  ' "${STAGE_DIR}/results.json" "${TASK_VERSION}" "${STAGE}" "${RUN_ID}" "${ATTEMPT}" "${COMMIT}" "${STARTED_AT}" "${reason}"
  node "${RESULTS_PARSER}" manifest --file "${RUN_MANIFEST}" --stage "${STAGE}" \
    --attempt "${ATTEMPT}" --stage-dir "${STAGE_DIR}" --exit 2 >/dev/null
  exit 2
}

# ── runner 原语 ───────────────────────────────────────────────────────────
run_vitest() { # $1=label 其余=vitest 目标/参数（JSON 报告与退出码落 run 目录）
  # 可选构成TC（C107F3-04 §13.3）：CONSTITUTES_TC 非空时经 W053 --constitutes 登记，
  # 仅本报告真实全绿才成立，调用方负责在调用后清除。
  local label="$1"; shift
  local safe="${label//[\/:]/-}"
  local json="${STAGE_DIR}/vitest-${safe}.json"
  local -a constitutes_args=()
  [ -n "${CONSTITUTES_TC:-}" ] && constitutes_args+=(--constitutes "${CONSTITUTES_TC}")
  printf '[%s] vitest %s\n' "$(now)" "$*"
  local code=0
  run_child "${STAGE_DIR}/vitest-${safe}.log" \
    npx vitest run --maxWorkers=1 --no-file-parallelism "$@" \
    --reporter=default --reporter=json --outputFile.json="${json}" || code=$?
  node "${RESULTS_PARSER}" vitest --file "${json}" --label "vitest:${label}" \
    --runner-exit "${code}" --accumulate "${ACC}" ${constitutes_args[@]+"${constitutes_args[@]}"} || true
  return "${code}"
}

run_backend_group() { # $1=组名（TEST_GROUP 子shell；TAP 写本 run 绝对目录） $2=可选构成TC（仅该组TAP真实全绿时由 W053 登记）
  local group="$1"
  local tap="${STAGE_DIR}/backend-${group}.tap"
  local -a constitutes_args=()
  [ -n "${2:-}" ] && constitutes_args+=(--constitutes "$2")
  # 可选（C107F3-04 §13.3）：EXTERNAL_SKIP_OK 非空时传 --allow-external-blocked-skip，
  # 仅豁免用例自身以 EXTERNAL_BLOCKED 声明的 skip（如实计实数），其余 skip 仍拒绝。
  [ -n "${EXTERNAL_SKIP_OK:-}" ] && constitutes_args+=(--allow-external-blocked-skip)
  printf '[%s] backend group %s (TEST_GROUP，TAP→%s)\n' "$(now)" "${group}" "${tap#"$REPO_ROOT"/}"
  local code=0
  # C107F3-02 §13.3 增量：node@24 默认 reporter 是 spec（v22 才默认 TAP），而
  # run-tests.mjs（W070 只读）不落 TAP 文件——原实现解析的 .tap 永不存在，真实
  # 绿组也会被记非法报告。子 shell 内显式 TAP reporter（只作用于本组 node 进程），
  # 原始输出进 log 后落 .tap（§12.3 条2：原始 TAP 写绝对 run 目录）；失败组的
  # not ok 行同样进 .tap，由 W053 结构解析如实计红，不吞失败。
  run_child_sh "${STAGE_DIR}/backend-${group}.log" \
    "cd platform-hypit/backend && export NODE_OPTIONS='--test-reporter=tap' && TEST_GROUP='${group}' npx --yes --package=node@24.14.1 -- node scripts/run-tests.mjs" || code=$?
  cp -f "${STAGE_DIR}/backend-${group}.log" "${tap}"
  node "${RESULTS_PARSER}" tap --file "${tap}" --label "backend:${group}" \
    --runner-exit "${code}" --accumulate "${ACC}" ${constitutes_args[@]+"${constitutes_args[@]}"} || true
  return "${code}"
}

run_java() { # $1..=完整类名（先 java-runtime 25；JUnit 立即复制防下次覆盖）
  local -a classes=("$@")
  printf '[%s] java(JDK25, 真实PG/Testcontainers): %s\n' "$(now)" "${classes[*]}"
  local code=0
  # 类名经环境传入子 shell（空格分隔；类名无空格）。
  export JAVA_TEST_CLASSES="${classes[*]}"
  run_child_sh "${STAGE_DIR}/gradle.log" '
    set -eu
    source scripts/lib/java-runtime.sh
    ensure_java_runtime 25 || exit 1
    cd platform-java
    tests_args=()
    for c in ${JAVA_TEST_CLASSES}; do tests_args+=(--tests "$c"); done
    ./gradlew :services:intelligence-service:test "${tests_args[@]}" \
      --rerun-tasks --no-daemon --no-parallel --max-workers=1
  ' || code=$?
  unset JAVA_TEST_CLASSES
  mkdir -p "${STAGE_DIR}/junit"
  for cls in "${classes[@]}"; do
    # 立即复制本次目标 XML，防止下一次运行覆盖与旧报告混入。
    # Gradle test-results 为扁平点号布局 TEST-<fqcn>.xml（§13.3 增量三：勿把类名 .→/ 转换再拼路径）。
    if [ -f "platform-java/services/intelligence-service/build/test-results/test/TEST-${cls}.xml" ]; then
      cp "platform-java/services/intelligence-service/build/test-results/test/TEST-${cls}.xml" \
        "${STAGE_DIR}/junit/TEST-${cls}.xml"
    fi
  done
  local name
  for cls in "${classes[@]}"; do
    name="${cls//\//.}"
    local xml="${STAGE_DIR}/junit/TEST-${name}.xml"
    if [ -f "${xml}" ]; then
      node "${RESULTS_PARSER}" junit --file "${xml}" --label "java:${cls}" \
        --runner-exit "${code}" --accumulate "${ACC}" || true
    else
      # 目标类没有任何 JUnit XML = 该类 0 执行或未编译——非法，不得判绿。
      node "${RESULTS_PARSER}" record --accumulate "${ACC}" --label "java:${cls}" \
        --exit "${code}" --problems "缺JUnit报告=${cls}（0执行或未编译）" >/dev/null
    fi
  done
  return "${code}"
}

run_fix2_card() { # $1=卡号（委托旧入口 card 层；FIX2_ART_BASE=本 stage 绝对目录）
  local card="$1"
  printf '[%s] fix2 card %s（FIX2_ART_BASE=%s，不裸调 all/local/recovery）\n' "$(now)" "${card}" "${STAGE_DIR#"$REPO_ROOT"/}"
  local code=0
  FIX2_ART_BASE="${STAGE_DIR}" run_child "${STAGE_DIR}/fix2-card-${card}.log" \
    bash "${FIX2_ENTRY}" --stage card --card "${card}" || code=$?
  record_runner "fix2card:${card}" "${code}"
  local results="${STAGE_DIR}/${card}/results.json"
  if [ -f "${results}" ]; then
    node "${RESULTS_PARSER}" layer --file "${results}" --label "fix2card:${card}" \
      --runner-exit "${code}" --accumulate "${ACC}" || true
  else
    node "${RESULTS_PARSER}" record --accumulate "${ACC}" --label "fix2card:${card}" \
      --exit "${code}" --problems "缺fix2 card产物=${results}" >/dev/null
  fi
  return "${code}"
}

# ── stage 就绪门槛（§12.3：尚未交付测试 exit 2 NOT_RUN，不提前要求业务通过） ──
BOOK="docs/任务书/草场任务书-107-fix-3-reviews.md"
W01='platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/fix2/HypitFix2C26IT.java'
W04='platform-hypit/backend/tests/engine/maintenance.test.ts'
W06='platform-hypit/backend/tests/engine/runner-isolation.test.ts'
W07='platform-hypit/backend/tests/agent-integration/fix2-c32.test.ts'
W27='src/views/video-clone/composables/fix2-c18.test.ts'
W29='platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/fix3/HypitFix3EvidenceTest.java'
W30='platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/fix3/HypitFix3EvidenceIT.java'
W31='platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/fix3/HypitFix3ArchiveIT.java'
W32='platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/fix3/HypitFix3AuthorContextIT.java'
W38='tests/e2e/hypit-fix2-journey.spec.ts'
W39='tests/e2e/hypit-fix3-reference.spec.ts'
W47='tests/deployment/hypit-fix3-live.contract.test.ts'
W63='platform-hypit/backend/tests/studio/fix3-writeback.test.ts'
W54F='tests/deployment/hypit-fix3-gates.contract.test.ts'

grep_tc() { # $1=TC $2=文件 → 0=发现
  [ -f "$2" ] && grep -q "$1" "$2"
}

book_all_cards_verified() {
  node -e '
    const fs = require("node:fs");
    const book = fs.readFileSync(process.argv[1], "utf8");
    const table = book.split("## 10. 开发计划与任务总表")[1].split("### 10.1")[0];
    const cards = ["01","02","03","04","05","06","07","08","09","10","11","12","13"];
    for (const c of cards) {
      const row = table.split("\n").find((l) => l.includes(`| C107F3-${c} `));
      if (!row) process.exit(1);
      const status = (row.split("|").slice(-2)[0] ?? "").trim();
      if (status !== "VERIFIED") process.exit(1);
    }
  ' "${BOOK}"
}

stage_ready() {
  case "${STAGE}" in
    tooling|fixtures) return 0 ;;
    variant) grep_tc 'TC-F2-26-02' "${W01}" ;;
    maintenance) grep_tc 'TC-F3-02-01' "${W04}" && grep_tc 'TC-F3-02-02' "${W04}" ;;
    engine)
      grep_tc 'TC-F3-03-01' "${W06}" \
        && { grep_tc 'TC-F3-03-02' "${W06}" || grep_tc 'TC-F3-03-02' "${W54F}" || grep_tc 'TC-F3-03-02' 'platform-hypit/backend/tests/engine/runner-daemon.ts' || grep_tc 'TC-F3-03-02' 'platform-hypit/backend/tests/engine/package-build.test.ts'; } ;;
    domain) grep_tc 'TC-F3-04-01' "${W07}" ;;
    evidence) grep_tc 'TC-F3-07-01' "${W29}" && grep_tc 'TC-F3-07-01' "${W30}" ;;
    vision) grep_tc 'TC-F3-08-01' "${W29}" && grep_tc 'TC-F3-08-01' "${W30}" ;;
    archive) grep_tc 'TC-F3-09-01' "${W31}" ;;
    context) grep_tc 'TC-F3-10-01' "${W32}" && grep_tc 'TC-F3-10-04' "${W27}" ;;
    e2e) [ -f "${W63}" ] && [ -f "${W39}" ] && { grep_tc 'TC-F3-11-01' "${W38}" || grep_tc 'TC-F3-11-01' "${W39}"; } ;;
    live-contract) grep_tc 'TC-F3-12-01' "${W47}" ;;
    docs) grep -q '"fix3"' contracts/hypit-coverage.v1.json 2>/dev/null ;;
    regression|all) book_all_cards_verified ;;
  esac
}

# ── 期望 TC（§12.2 唯一索引；W053 在 emit 层做全发现核验） ──────────────────
stage_expect() {
  case "${STAGE}" in
    tooling) echo 'TC-F3-13-01,TC-F3-13-02,TC-F3-13-03' ;;
    fixtures) echo 'TC-F3-13-03' ;;
    variant) echo 'TC-F3-01-01,TC-F3-01-02,TC-F3-01-03,TC-F2-26-02' ;;
    maintenance) echo 'TC-F3-02-01,TC-F3-02-02' ;;
    engine) echo 'TC-F3-03-01,TC-F3-03-02' ;;
    domain) echo 'TC-F3-04-01,TC-F3-04-02,TC-F3-04-03,TC-F3-04-04' ;;
    evidence) echo 'TC-F3-07-01,TC-F3-07-02,TC-F3-07-03' ;;
    vision) echo 'TC-F3-08-01,TC-F3-08-02,TC-F3-08-03' ;;
    archive) echo 'TC-F3-09-01,TC-F3-09-02,TC-F3-09-03' ;;
    context) echo 'TC-F3-10-01,TC-F3-10-02,TC-F3-10-03,TC-F3-10-04' ;;
    e2e) echo 'TC-F3-11-01,TC-F3-11-02,TC-F3-11-03,TC-F2-36-01,TC-F2-36-02,TC-F2-36-03,TC-F2-36-04,TC-F2-37-01,TC-F2-37-02,TC-F2-37-03,TC-F2-37-04' ;;
    live-contract) echo 'TC-F3-12-01,TC-F3-12-02,TC-F3-12-03' ;;
    docs) echo '' ;;  # 人工逐项核验 TC 由 C05/C06 结构化登记；脚本只核验命令与报告
    regression) echo 'TC-F3-06-01,TC-F3-06-03' ;;
    all) echo 'TC-F3-06-01,TC-F3-06-02,TC-F3-06-03' ;;
  esac
}

# ── Docker/锁守卫边界（步骤1）：受委托脚本独立持锁；直接 Java/Node 阶段接 W068；
#    all/委派/无栈 stage 父层不抢锁。 ────────────────────────────────────────
if [ "${LOCAL_STACK_ENTRY:-}" != "${SELF}" ]; then
  case "${STAGE}" in
    evidence|vision|archive|context|regression)
      local_stack_enter y1-hypit-fix3 "${SELF}" --docker --cleanup -- "${ORIGINAL_ARGS[@]}" ;;
    maintenance)
      local_stack_enter y1-hypit-fix3 "${SELF}" --cleanup -- "${ORIGINAL_ARGS[@]}" ;;
  esac
fi

# ── stage 实现 ────────────────────────────────────────────────────────────
stage_tooling() {
  run_vitest "tooling" \
    tests/deployment/hypit-fix3-spec.contract.test.ts \
    tests/deployment/hypit-fix3-gates.contract.test.ts
  finish_stage "$(stage_expect)"
}

stage_fixtures() {
  printf '[%s] fixtures：W064 单 FFmpeg 串行生成 F-REF\n' "$(now)"
  local gcode=0 vcode=0
  run_child "${STAGE_DIR}/fixtures-generate.log" node scripts/acceptance/hypit-fix3-fixtures.mjs \
    --out "${STAGE_DIR}/fixtures" --json-out "${STAGE_DIR}/fixtures-generate.json" || gcode=$?
  record_runner "fixtures:generate" "${gcode}"
  [ "${gcode}" -eq 0 ] || { finish_stage "$(stage_expect)"; return; }
  run_child "${STAGE_DIR}/fixtures-verify.log" node scripts/acceptance/hypit-fix3-fixtures.mjs \
    --verify "${STAGE_DIR}/fixtures" --json-out "${STAGE_DIR}/fixtures-verify.json" || vcode=$?
  record_runner "fixtures:verify" "${vcode}"
  if node "${RESULTS_PARSER}" verify-manifest --file "${STAGE_DIR}/fixtures/manifest.json" >/dev/null; then
    record_runner "fixtures:manifest" 0
  else
    record_problem "fixtures:manifest" "manifest sha 核验失败（W053 verify-manifest 非零）"
  fi
  # TC-F3-13-03 必须以真实测试执行被发现。vitest -t 过滤无法在 JSON 结构上区分
  # 「被过滤排除」与「真实 skip」（同为 pending），为保持 skipped=0 严格门禁不虚报，
  # 这里整文件运行 gates 契约（TC-F3-13-03 含真实 FFmpeg 生成断言，串行）。
  run_vitest "fixtures-gates" tests/deployment/hypit-fix3-gates.contract.test.ts
  finish_stage "$(stage_expect)"
}

stage_variant() {
  run_fix2_card C107F2-26
  finish_stage "$(stage_expect)"
}

stage_maintenance() {
  run_backend_group engine
  run_backend_group agent-integration
  finish_stage "$(stage_expect)"
}

stage_engine() {
  run_backend_group engine
  run_fix2_card C107F2-32
  # C107F3-03 §13.3 增量：W54 传导复核整文件运行——vitest v3.2.7 在 `-t 'TC-F3-03'`
  # 过滤下未匹配用例 status=skipped（实证 numPendingTests=N），W053 vitest 门禁按
  # RULE-015 记 skipped>0 问题、emit 必然假红；整文件运行保持 skip/todo=0 真实门禁
  # 并顺带复核 TC-F3-13 门禁组（与 tooling 契约一致）。不改 stage 编排/守卫/期望表。
  if grep_tc 'TC-F3-03-02' "${W54F}"; then
    run_vitest "engine-propagation" tests/deployment/hypit-fix3-gates.contract.test.ts
  fi
  finish_stage "$(stage_expect)"
}

stage_domain() {
  run_fix2_card C107F2-32
  # 域执行TC（C107F3-04 §13.3；§12.2 TC-F3-04-02/03/04）：三域由本 stage 串行实跑，
  # 其原始报告即 TC 证据；--constitutes 仅在该域报告真实全绿（executed>0 且
  # failed/skip/todo=0、子进程退出0）时由 W053 登记对应 TC，任何失败不登记。
  run_backend_group studio TC-F3-04-02
  # media 腿（C107F3-04 §13.3）：whisperx 真实 ASR 用例按 §1.4 范围外、由用例自身
  # 以 EXTERNAL_BLOCKED 声明跳过——仅该类声明 skip 记实数（skipped/externalBlocked），
  # 其余任何 skip/todo/失败仍非零；TC-F3-04-03 仅要求「当前实数可还原」。
  EXTERNAL_SKIP_OK=1
  run_backend_group media TC-F3-04-03
  EXTERNAL_SKIP_OK=""
  # src/views/video-clone 全域（shell 展开非空；无匹配即失败，不得静默缩小范围）。
  local -a vc_tests=()
  while IFS= read -r f; do vc_tests+=("$f"); done < <(find src/views/video-clone -name '*.test.ts' | sort)
  if [ "${#vc_tests[@]}" -eq 0 ]; then
    printf 'FAIL domain：src/views/video-clone 无任何 *.test.ts（范围不得缩小）\n'
    node "${RESULTS_PARSER}" record --accumulate "${ACC}" --label "vitest:video-clone-domain" \
      --exit 1 --problems "src/views/video-clone 零测试文件" >/dev/null
  else
    CONSTITUTES_TC="TC-F3-04-04"
    run_vitest "video-clone-domain" "${vc_tests[@]}"
    CONSTITUTES_TC=""
  fi
  finish_stage "$(stage_expect)"
}

stage_evidence() {
  run_java \
    com.grassland.intelligence.hypit.fix3.HypitFix3EvidenceTest \
    com.grassland.intelligence.hypit.fix3.HypitFix3EvidenceIT \
    com.grassland.intelligence.hypit.fix2.HypitFix2C16IT
  # media 腿（C107F3-07 §13.3）：whisperx 真实 ASR 用例按 §1.4 范围外、由用例自身
  # 以 EXTERNAL_BLOCKED 声明跳过——仅该类声明 skip 记实数（skipped/externalBlocked），
  # 其余任何 skip/todo/失败仍非零；W41 的真实 probe/frames/归一化契约用例零 skip。
  EXTERNAL_SKIP_OK=1
  run_backend_group media
  EXTERNAL_SKIP_OK=""
  finish_stage "$(stage_expect)"
}

stage_vision() {
  run_java \
    com.grassland.intelligence.hypit.fix3.HypitFix3EvidenceTest \
    com.grassland.intelligence.hypit.fix3.HypitFix3EvidenceIT \
    com.grassland.intelligence.hypit.fix2.HypitFix2C16IT
  # media 腿（C107F3-07 §13.3）：同 stage_evidence——仅豁免 EXTERNAL_BLOCKED 声明 skip。
  EXTERNAL_SKIP_OK=1
  run_backend_group media
  EXTERNAL_SKIP_OK=""
  finish_stage "$(stage_expect)"
}

stage_archive() {
  run_java com.grassland.intelligence.hypit.fix3.HypitFix3ArchiveIT
  finish_stage "$(stage_expect)"
}

stage_context() {
  run_java \
    com.grassland.intelligence.hypit.fix3.HypitFix3AuthorContextIT \
    com.grassland.intelligence.hypit.agent.HypitPlannerParseTest
  run_vitest "context-frontend" \
    src/views/video-clone/composables/fix2-c18.test.ts \
    src/views/video-clone/components/ClonePlanPanel.test.ts
  finish_stage "$(stage_expect)"
}

stage_e2e() {
  # 先 studio 组（W63 先行）；其子进程结束后才进入 E2E 固定入口。
  run_backend_group studio
  # §9.3 唯一 y1-e2e-local fresh 组合；旗标与三 spec 清单按 §12.3 条5 固定。
  export HYPIT_E2E=1
  export HYPIT_FIX2_TEXT_FIXTURE=1
  export DH_E2E=0
  export DH_FIX2_E2E=0
  export CANVAS_E2E_TEXT_FIXTURE=0
  export E2E_WORKERS=1
  export E2E_ENGINES='chromium firefox webkit'
  export HYPIT_FIX3_EVIDENCE_ROOT="${STAGE_DIR}"
  mkdir -p "${STAGE_DIR}/reports"
  # 复用 W067 现有逐引擎原始 JUnit 复制扩展点（变量旧名不代表 103 任务）。
  export TASK103_EVIDENCE_DIR="${STAGE_DIR}/reports"
  export E2E_SPECS='tests/e2e/hypit-fix2-journey.spec.ts tests/e2e/hypit-fix2-c36.spec.ts tests/e2e/hypit-fix3-reference.spec.ts'
  printf '[%s] e2e：ci-e2e-107（y1-e2e-local fresh，三引擎串行；spec=%s）\n' "$(now)" "${E2E_SPECS}"
  local code=0
  run_child "${STAGE_DIR}/ci-e2e.log" bash scripts/acceptance/ci-e2e-107.sh || code=$?
  # 逐引擎原始 JUnit 解析（ci-e2e 复制到 TASK103_EVIDENCE_DIR/<engine>/）。
  local engine xml ecode
  for engine in chromium firefox webkit; do
    xml="${STAGE_DIR}/reports/${engine}/playwright-results.xml"
    ecode="${code}"
    if [ -f "${xml}" ]; then
      node "${RESULTS_PARSER}" junit --file "${xml}" --label "e2e:${engine}" \
        --runner-exit "${ecode}" --accumulate "${ACC}" || true
    else
      node "${RESULTS_PARSER}" record --accumulate "${ACC}" --label "e2e:${engine}" \
        --exit "${ecode}" --problems "缺引擎报告=${engine}（reports/${engine}/playwright-results.xml）" >/dev/null
    fi
  done
  finish_stage "$(stage_expect)"
}

stage_live_contract() {
  run_vitest "live-contract" tests/deployment/hypit-fix3-live.contract.test.ts
  finish_stage "$(stage_expect)"
}

stage_docs() {
  printf '[%s] docs:links（基线 exit1/errors=0/unindexed≤72 可接受）\n' "$(now)"
  local lcode=0 baseline_bad=0
  run_child "${STAGE_DIR}/docs-links.log" npm run docs:links || lcode=$?
  node -e '
    const fs = require("node:fs");
    const log = fs.readFileSync(process.argv[1], "utf8");
    const exit = Number(process.argv[2]);
    const err = Number((log.match(/errors[=: ]+(\d+)/) ?? [])[1] ?? "-1");
    const un = Number((log.match(/unindexed[=: ]+(\d+)/) ?? [])[1] ?? "-1");
    const baselineOk = err === 0 && un >= 0 && un <= 72;
    if (!((exit === 0) || (exit === 1 && baselineOk))) process.exit(1);
  ' "${STAGE_DIR}/docs-links.log" "${lcode}" || baseline_bad=1
  if [ "${baseline_bad}" -eq 1 ]; then
    record_problem "docs:links" "docs:links 超出基线（exit=${lcode}，详见 docs-links.log）"
  else
    record_runner "docs:links" 0
  fi
  run_checked "docs:status" "${STAGE_DIR}/docs-status.log" npm run docs:status
  run_vitest "docs-contracts" \
    tests/deployment/hypit-fix2-spec.contract.test.ts \
    tests/deployment/hypit-fix3-spec.contract.test.ts
  finish_stage "$(stage_expect)"
}

stage_regression() {
  run_checked "npm:typecheck" "${STAGE_DIR}/typecheck.log" npm run typecheck
  run_checked "npm:lint" "${STAGE_DIR}/lint.log" npm run lint
  run_checked "npm:quality:lifecycle" "${STAGE_DIR}/quality-lifecycle.log" npm run quality:lifecycle
  run_checked "npm:security:secrets" "${STAGE_DIR}/security-secrets.log" npm run security:secrets
  run_checked "design:lint" "${STAGE_DIR}/design-lint.log" npx '@google/design.md' lint DESIGN.md
  # 前端域完整匹配（shell 展开非空）。
  local -a fe=()
  local f
  while IFS= read -r f; do fe+=("$f"); done < <(find src/views/video-clone -name '*.test.ts' | sort)
  for f in tests/deployment/hypit*.test.ts; do [ -f "$f" ] && fe+=("$f"); done
  if [ "${#fe[@]}" -eq 0 ]; then
    record_problem "vitest:regression-frontend" "前端域零匹配文件（范围不得缩小）"
  else
    run_vitest "regression-frontend" "${fe[@]}"
  fi
  run_checked_sh "backend:typecheck" "${STAGE_DIR}/backend-typecheck.log" \
    'cd platform-hypit/backend && npm run typecheck'
  local group
  for group in engine workspace media providers runtime studio agent-integration; do
    run_backend_group "${group}"
  done
  # Java hypit 全域（实际发现清单核对：JUnit classes 必须落在 hypit 包）。
  printf '[%s] java hypit.* 全域\n' "$(now)"
  local jcode=0
  run_child_sh "${STAGE_DIR}/gradle.log" '
    set -eu
    source scripts/lib/java-runtime.sh
    ensure_java_runtime 25 || exit 1
    cd platform-java
    ./gradlew :services:intelligence-service:test --tests "com.grassland.intelligence.hypit.*" \
      --rerun-tasks --no-daemon --no-parallel --max-workers=1
  ' || jcode=$?
  mkdir -p "${STAGE_DIR}/junit"
  local xml cls found_any=0
  for xml in platform-java/services/intelligence-service/build/test-results/test/TEST-*.xml; do
    [ -f "${xml}" ] || continue
    cls="$(basename "${xml}" .xml)"
    case "${cls}" in TEST-com.grassland.intelligence.hypit.*) ;;
      *) continue ;;  # 只复制本次 hypit 目标，防旧报告混入
    esac
    cp "${xml}" "${STAGE_DIR}/junit/${cls}.xml"
    found_any=1
    node "${RESULTS_PARSER}" junit --file "${STAGE_DIR}/junit/${cls}.xml" \
      --label "java:${cls#TEST-}" --runner-exit "${jcode}" --accumulate "${ACC}" || true
  done
  if [ "${jcode}" -ne 0 ]; then
    record_problem "gradle:hypit.*" "gradle hypit.* 非零（exit=${jcode}）"
  fi
  [ "${found_any}" -eq 1 ] || record_problem "gradle:hypit.*" "无 hypit.* JUnit 报告（实际发现清单为空）"
  # docs 两检查 + git diff --check。
  local lcode=0 baseline_bad=0
  run_child "${STAGE_DIR}/docs-links.log" npm run docs:links || lcode=$?
  node -e '
    const fs = require("node:fs");
    const log = fs.readFileSync(process.argv[1], "utf8");
    const exit = Number(process.argv[2]);
    const err = Number((log.match(/errors[=: ]+(\d+)/) ?? [])[1] ?? "-1");
    const un = Number((log.match(/unindexed[=: ]+(\d+)/) ?? [])[1] ?? "-1");
    process.exit((exit === 0 || (exit === 1 && err === 0 && un >= 0 && un <= 72)) ? 0 : 1);
  ' "${STAGE_DIR}/docs-links.log" "${lcode}" || baseline_bad=1
  if [ "${baseline_bad}" -eq 1 ]; then
    record_problem "docs:links" "docs:links 超基线（exit=${lcode}）"
  else
    record_runner "docs:links" 0
  fi
  run_checked "docs:status" "${STAGE_DIR}/docs-status.log" npm run docs:status
  local dcode=0
  git diff --check >"${STAGE_DIR}/git-diff-check.log" 2>&1 || dcode=$?
  if [ "${dcode}" -eq 0 ]; then record_runner "git:diff-check" 0; else
    record_problem "git:diff-check" "git diff --check 非零（详见 git-diff-check.log）"
  fi
  finish_stage "$(stage_expect)"
}

stage_all() {
  # V-15：同 run 串行重入各 stage（各自按其守卫边界持锁）；不递归 all。
  local -a order=(tooling fixtures variant maintenance engine domain evidence vision archive context e2e live-contract docs regression)
  local failed="" s code
  for s in "${order[@]}"; do
    printf '[%s] all → %s\n' "$(now)" "${s}"
    code=0
    bash "${SELF}" --stage "${s}" --run "${RUN_ID}" || code=$?
    if [ "${code}" -ne 0 ]; then
      failed="${s}"
      printf 'FAIL all：stage %s exit=%s（后续 stage 不执行，避免带红汇总）\n' "${s}" "${code}"
      break
    fi
  done
  if [ -n "${failed}" ]; then
    node "${RESULTS_PARSER}" manifest --file "${RUN_MANIFEST}" --stage "all" \
      --attempt "${ATTEMPT}" --stage-dir "${STAGE_DIR}" --exit 1 >/dev/null
    exit 1
  fi
  local gcode=0
  node "${RESULTS_PARSER}" final-gates --run-dir "${RUN_DIR}" --run-id "${RUN_ID}" \
    >"${STAGE_DIR}/final-gates.log" 2>&1 || gcode=$?
  tail -1 "${STAGE_DIR}/final-gates.log" || true
  node "${RESULTS_PARSER}" manifest --file "${RUN_MANIFEST}" --stage "all" \
    --attempt "${ATTEMPT}" --stage-dir "${STAGE_DIR}" --exit "${gcode}" >/dev/null
  exit "${gcode}"
}

# 就绪门槛：未交付测试一律 NOT_RUN（exit 2），不提前要求业务通过。
if ! stage_ready; then
  case "${STAGE}" in
    variant) not_run "等待 C107F3-01 交付 TC-F2-26-02（W01）" ;;
    maintenance) not_run "等待 C107F3-02 交付 TC-F3-02-01/02（W04）" ;;
    engine) not_run "等待 C107F3-03 交付 TC-F3-03-01/02（W06/W43/W44）" ;;
    domain) not_run "等待 C107F3-04 交付 TC-F2-32-04 独立子测试（W07）" ;;
    evidence) not_run "等待 C107F3-07 交付 TC-F3-07-01～03（W29/W30）" ;;
    vision) not_run "等待 C107F3-08 交付 TC-F3-08-01～03（W29/W30）" ;;
    archive) not_run "等待 C107F3-09 交付 TC-F3-09-01～03（W31）" ;;
    context) not_run "等待 C107F3-10 交付 TC-F3-10-01～04（W32/W27）" ;;
    e2e) not_run "等待 C107F3-11 交付 W63/W39 与 TC-F3-11-01～03" ;;
    live-contract) not_run "等待 C107F3-12 交付 W47（TC-F3-12-01～03）" ;;
    docs) not_run "等待 C107F3-05 在 W02 登记 fix3（coverage.fix3）" ;;
    regression|all) not_run "等待全部 12 卡 VERIFIED（本书 §10 状态表）后方可最终回归" ;;
  esac
fi

case "${STAGE}" in
  tooling) stage_tooling ;;
  fixtures) stage_fixtures ;;
  variant) stage_variant ;;
  maintenance) stage_maintenance ;;
  engine) stage_engine ;;
  domain) stage_domain ;;
  evidence) stage_evidence ;;
  vision) stage_vision ;;
  archive) stage_archive ;;
  context) stage_context ;;
  e2e) stage_e2e ;;
  live-contract) stage_live_contract ;;
  docs) stage_docs ;;
  regression) stage_regression ;;
  all) stage_all ;;
esac
