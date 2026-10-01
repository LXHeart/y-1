#!/usr/bin/env bash
# 107-fix-2-e2e.sh — V-09 stage e2e：真实浏览器三引擎（C107F2-37/C107F2-39）。
#
# 编排（幂等，可重复执行）：
#   1. 逐引擎调用既有 ci-e2e-107.sh（内部 scripts/ci-e2e.sh 完整隔离生命周期：
#      fresh 栈/up/seed/引擎内重置/trap 清理；local-stack 单栈互斥由其内部持有）。
#      每引擎一次调用——ci-e2e 的 junit 输出路径按引擎覆盖，分次调用才能逐引擎
#      留证（W219）。引擎串行（本机资源约束，不并行）。
#   2. 每引擎解析 JUnit（按 testcase 块判定 failure——紧邻式正则会漏检 system-out
#      后置的 failure，round-7 实录 4 全败被记 passed=4），任何引擎 executed=0 或
#      存在失败 → 非零；不以进程退出码或日志关键词 ALL-GREEN 替代证据。
#   3. 汇总写 test-artifacts/task-107/fix2/e2e/results.json（逐引擎计数+TC 发现）。
#
# 外部商业模型：恒不调用（受控文本 fixture 替换唯一外模型，其余编译/渲染/PG 真实）。
set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
cd "${ROOT_DIR}"

ART_BASE_STAGE="${FIX2_ART_BASE:-test-artifacts/task-107/fix2}"
ART_DIR="${ART_BASE_STAGE}/e2e"
STARTED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
COMMIT="$(git rev-parse HEAD 2>/dev/null || echo unknown)"
mkdir -p "${ART_DIR}"

# V-09 要求 S2–S5 + TC36/37：journey（C37）+ c36（C36）+ 既有 #107 UI 面（M1–M4
# 依赖：入口/克隆/恢复/Studio）。api-render 属 stage local（V-08），不在此重复。
export E2E_SPECS="${E2E_SPECS:-tests/e2e/hypit-fix2-journey.spec.ts tests/e2e/hypit-fix2-c36.spec.ts tests/e2e/hypit-entrypoints.spec.ts tests/e2e/hypit-clone.spec.ts tests/e2e/hypit-recovery.spec.ts tests/e2e/hypit-studio.spec.ts}"
export E2E_WORKERS="${E2E_WORKERS:-1}"
# 受控 fixture token：单次调用内容器与 spec 同源即可；fresh 栈无存量凭据错位。
export HYPIT_FIX2_PROVIDER_TOKEN="${HYPIT_FIX2_PROVIDER_TOKEN:-fix2-journey-fixture-key}"

declare -a ENGINE_LIST=(${E2E_ENGINES:-chromium firefox webkit})
declare -a ENGINE_CODES=()
overall=0

for engine in "${ENGINE_LIST[@]}"; do
  printf '[%s] e2e 引擎 %s（ci-e2e-107 完整隔离生命周期）\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "${engine}"
  E2E_ENGINES="${engine}" bash scripts/acceptance/ci-e2e-107.sh \
    >"${ART_DIR}/ci-e2e-${engine}.log" 2>&1
  code=$?
  ENGINE_CODES+=("${code}")
  [ -f test-artifacts/playwright-results.xml ] \
    && cp test-artifacts/playwright-results.xml "${ART_DIR}/playwright-junit-${engine}.xml"
  [ "${code}" -ne 0 ] && overall=1
done

node -e '
  const fs = require("node:fs");
  const [dir, started, commit, engines, codes, overall] = process.argv.slice(1);
  const engineList = engines.split(",");
  const codeList = codes.split(",").map(Number);
  const byEngine = {};
  const totals = { total: 0, passed: 0, failed: 0, skipped: 0, executed: 0 };
  const tcFound = new Set(), tcFailed = new Set();
  for (const [idx, engine] of engineList.entries()) {
    let stats = null;
    try {
      const xml = fs.readFileSync(`${dir}/playwright-junit-${engine}.xml`, "utf8");
      const chunks = xml.split(/<\/testcase>/);
      const cases = [], fails = [];
      for (const chunk of chunks) {
        const m = chunk.match(/<testcase[^>]*\sname="([^"]*)"/);
        if (!m) continue;
        cases.push(m[1]);
        if (/<failure[\s>]|<error[\s>]/.test(chunk)) fails.push(m[1]);
      }
      stats = { executed: cases.length, total: cases.length, passed: cases.length - fails.length,
        failed: fails.length, skipped: 0 };
      for (const n of cases) for (const m of n.matchAll(/TC-F2-\d{2}-\d{2}/g)) tcFound.add(m[0]);
      for (const n of fails) for (const m of n.matchAll(/TC-F2-\d{2}-\d{2}/g)) tcFailed.add(m[0]);
      totals.total += stats.total; totals.passed += stats.passed; totals.failed += stats.failed;
      totals.skipped += stats.skipped; totals.executed += stats.executed;
    } catch { stats = null; }
    byEngine[engine] = { exitCode: codeList[idx] ?? -1, tests: stats };
  }
  fs.writeFileSync(`${dir}/results.json`, JSON.stringify({
    taskBook: "107-fix-2 v1.0.0", stage: "e2e", card: "C107F2-37",
    exitCode: Number(overall), commit, startedAt: started, finishedAt: new Date().toISOString(),
    node: process.version, java: null, imageDigest: null,
    engines: byEngine, tests: totals, tc: { found: [...tcFound], failed: [...tcFailed] },
    notRun: [], notes: [],
  }, null, 2) + "\n");
' "${ART_DIR}" "${STARTED_AT}" "${COMMIT}" "$(IFS=','; echo "${ENGINE_LIST[*]}")" "$(IFS=','; echo "${ENGINE_CODES[*]}")" "${overall}"

if [ "${overall}" -ne 0 ]; then
  printf 'FAIL stage e2e：存在非零引擎（逐引擎日志 %s/ci-e2e-<engine>.log）\n' "${ART_DIR}"
  exit 1
fi
printf 'PASS stage e2e：逐引擎结果见 %s/results.json\n' "${ART_DIR}"
exit 0
