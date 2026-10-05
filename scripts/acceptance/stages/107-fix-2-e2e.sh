#!/usr/bin/env bash
# 107-fix-2-e2e.sh — V-09 stage e2e：真实浏览器三引擎（C107F2-37/C107F2-39）。
#
# 编排（幂等，可重复执行）：
#   1. 逐引擎调用既有 ci-e2e-107.sh（内部 scripts/ci-e2e.sh 完整隔离生命周期：
#      fresh 栈/up/seed/引擎内重置/trap 清理；local-stack 单栈互斥由其内部持有）。
#      每引擎一次调用——ci-e2e 的 junit 输出路径按引擎覆盖，分次调用才能逐引擎
#      留证（W219）。引擎串行（本机资源约束，不并行）。
#   2. 每引擎经 W053（hypit-fix3-results.mjs）按真实 JUnit 结构解析 testcase 块
#      （skipped/todo 为结构实数——禁止硬填 0），断言：报告存在（缺引擎→非零）、
#      executed>0、failed/skipped/todo=0、必需 TC 全发现；results.json 与原始
#      JUnit 不一致按「旧结果重贴」拒绝（见 W050 的 layer 复核）。不以进程退出码
#      或日志关键词 ALL-GREEN 替代证据。
#   3. 汇总写 $FIX2_ART_BASE/e2e/results.json（generator=w53，逐引擎真实计数+TC）。
#
# E2E_GATE_ONLY=1：跳过引擎执行，仅对既有产物做第2步门禁复核（W054 隔离注入与
# 复验入口；正常路径不设置）。E2E_EXPECT_ENGINES/E2E_EXPECT_TCS 可显式覆盖。
#
# 外部商业模型：恒不调用（受控文本 fixture 替换唯一外模型，其余编译/渲染/PG 真实）。
set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
cd "${ROOT_DIR}"

RESULTS_PARSER="scripts/acceptance/hypit-fix3-results.mjs"
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

if [ "${E2E_GATE_ONLY:-0}" != "1" ]; then
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
fi

# ── 门禁层（真实解析；缺引擎/零执行/失败/skip/缺TC → 非零） ─────────────────
declare -a GATE_ENGINES=(${E2E_EXPECT_ENGINES:-chromium firefox webkit})
E2E_EXPECT_TCS="${E2E_EXPECT_TCS:-TC-F2-36-01,TC-F2-36-02,TC-F2-36-03,TC-F2-36-04,TC-F2-37-01,TC-F2-37-02,TC-F2-37-03,TC-F2-37-04}"

for engine in "${GATE_ENGINES[@]}"; do
  xml="${ART_DIR}/playwright-junit-${engine}.xml"
  if [ -f "${xml}" ]; then
    node "${RESULTS_PARSER}" junit --file "${xml}" --label "e2e:${engine}" \
      --expect "${E2E_EXPECT_TCS}" --json-out "${ART_DIR}/parse-${engine}.json" \
      >>"${ART_DIR}/gate.log" 2>&1 || true
  else
    printf '{"label":"e2e:%s","stats":null,"ok":false,"problems":["缺引擎报告=%s"]}\n' "${engine}" "${engine}" \
      >"${ART_DIR}/parse-${engine}.json"
  fi
done

node -e '
  const fs = require("node:fs");
  const path = require("node:path");
  const [dir, started, commit, enginesArg, codesArg] = process.argv.slice(1);
  const engines = enginesArg.split(",").filter(Boolean);
  const codes = codesArg.split(",").filter((s) => s.length > 0).map(Number);
  const problems = [];
  const byEngine = {};
  const totals = { total: 0, passed: 0, failed: 0, skipped: 0, todo: 0, executed: 0 };
  const tcFound = new Set(); const tcFailed = new Set();
  for (const [idx, engine] of engines.entries()) {
    const code = codes[idx] ?? null;
    let ev = null;
    try { ev = JSON.parse(fs.readFileSync(path.join(dir, `parse-${engine}.json`), "utf8")); } catch {}
    if (code !== null && code !== 0) problems.push(`引擎 ${engine} 子进程 exit=${code}`);
    if (!ev) { problems.push(`e2e/${engine}: 无解析结果`); byEngine[engine] = { exitCode: code, tests: null }; continue; }
    for (const p of ev.problems ?? []) problems.push(p);
    if (ev.stats) {
      byEngine[engine] = { exitCode: code, tests: ev.stats };
      for (const k of Object.keys(totals)) totals[k] += ev.stats[k] ?? 0;
    } else {
      byEngine[engine] = { exitCode: code, tests: null };
    }
    for (const tc of ev.found ?? []) tcFound.add(tc);
  }
  const exitCode = problems.length === 0 ? 0 : 1;
  fs.writeFileSync(path.join(dir, "results.json"), JSON.stringify({
    taskBook: "107-fix-2 v1.0.0", stage: "e2e", card: "C107F2-37",
    generator: "w53",
    exitCode, commit, startedAt: started, finishedAt: new Date().toISOString(),
    node: process.version, java: null, imageDigest: null,
    engines: byEngine, tests: totals,
    tc: { found: [...tcFound].sort(), failed: [...tcFailed].sort() },
    notRun: [], notes: [], problems,
  }, null, 2) + "\n");
  for (const p of problems) console.log("  - " + p);
  console.log(`stage e2e gate: exitCode=${exitCode} executed=${totals.executed} failed=${totals.failed} skipped=${totals.skipped} todo=${totals.todo} tcFound=${tcFound.size}`);
  process.exit(exitCode);
' "${ART_DIR}" "${STARTED_AT}" "${COMMIT}" "$(IFS=','; echo "${GATE_ENGINES[*]}")" "$(IFS=','; echo "${ENGINE_CODES[*]}")"
gate_code=$?

if [ "${gate_code}" -ne 0 ]; then
  printf 'FAIL stage e2e：门禁未过（逐引擎日志 %s/ci-e2e-<engine>.log；问题清单见 results.json.problems）\n' "${ART_DIR}"
  exit 1
fi
printf 'PASS stage e2e：%s/results.json（逐引擎真实解析）\n' "${ART_DIR}"
exit 0
