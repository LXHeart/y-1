#!/usr/bin/env bash
# 107-fix-2-all.sh — V-11 stage all：全层汇总与最终集成出口（C107F2-39/C107F2-40）。
#
# 判定原则（§12.3 V-11 / §12.5）：
#   - 只解析各层实际产物（results.json），不以日志关键词或「上次跑过」代替；
#     任一必需层缺产物/exitCode≠0/executed=0/存在失败 → 非零并逐项列出缺项。
#   - LIVE（真实商业 Provider）未授权恒 NOT_RUN：本地全绿只能表述为
#     「107-fix-2 本地交付验收 LOCAL_PASS」，绝无全平台全量通过表述。
#   - G1–G7 Gate 由各层证据映射；无法证明的 Gate 如实 NOT_RUN/PARTIAL。
set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
cd "${ROOT_DIR}"

ART_BASE_STAGE="${FIX2_ART_BASE:-test-artifacts/task-107/fix2}"
ART_DIR="${ART_BASE_STAGE}/all"
STARTED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
COMMIT="$(git rev-parse HEAD 2>/dev/null || echo unknown)"
mkdir -p "${ART_DIR}"

declare -a MISSING=()
declare -a FAILED=()

check_layer() {
  local layer="$1" path="$2" expected="$3"
  if [ ! -f "${path}" ]; then
    MISSING+=("${layer}:${path}")
    return
  fi
  node -e '
    const fs = require("node:fs");
    const [layer, path, expected] = process.argv.slice(1);
    let r;
    try {
      r = JSON.parse(fs.readFileSync(path, "utf8"));
    } catch (e) {
      console.log(`${layer}: 产物不可读（${e.message}）`);
      process.exit(1);
    }
    const problems = [];
    if (r.exitCode !== 0) problems.push(`exitCode=${r.exitCode}`);
    const t = r.tests ?? (r.engines ? Object.values(r.engines).reduce((acc, e) => {
      if (!e.tests) return acc;
      for (const k of ["total", "passed", "failed", "skipped", "executed"]) acc[k] = (acc[k] ?? 0) + e.tests[k];
      return acc;
    }, {}) : null);
    if (!t) problems.push("无测试计数");
    else {
      if ((t.executed ?? 0) <= 0) problems.push("executed=0（零用例不能判绿）");
      if ((t.failed ?? 0) > 0) problems.push(`failed=${t.failed}`);
      if ((t.skipped ?? 0) > 0) problems.push(`skipped=${t.skipped}`);
    }
    // §12.3 步骤2：预期 TC 发现数——缺失任一必需 TC（被过滤/skip/证据被删）
    // 即非零，不以「其余用例跑过且绿」放行。
    const found = new Set(r.tc?.found ?? []);
    for (const tc of (expected ? expected.split(",") : [])) {
      if (!found.has(tc)) problems.push(`缺必需TC=${tc}`);
    }
    if (problems.length > 0) { console.log(`${layer}: ${problems.join("；")}`); process.exit(1); }
  ' "${layer}" "${path}" "${expected}" || FAILED+=("${layer}")
}

# 契约层：门禁反向校验（TC-F2-39-02 四故障捕获）+ 装配契约实跑，不读旧产物。
# FIX2_ALL_SKIP_CONTRACT=1：仅限 gates 契约测试自身调用本脚本时防递归（生产路径
# 不设此值；跳过时 G7 如实 NOT_RUN，不会伪绿）。
contract_code=-1
if [ "${FIX2_ALL_SKIP_CONTRACT:-0}" != "1" ]; then
  contract_code=0
  printf '[%s] 契约层：gates/compose/entrypoint 契约测试实跑\n' "${STARTED_AT}"
  npx vitest run tests/deployment/hypit-fix2-gates.contract.test.ts \
    tests/deployment/hypit-compose.contract.test.ts \
    tests/deployment/hypit-entrypoint.contract.test.ts \
    >"${ART_DIR}/contract-vitest.log" 2>&1
  contract_code=$?
  [ "${contract_code}" -ne 0 ] && FAILED+=("contract(三层装配/反向门禁 vitest exit=${contract_code})")
fi

check_layer local "${ART_BASE_STAGE}/local/results.json" "TC-F2-08-01,TC-F2-08-02,TC-F2-08-03,TC-F2-08-04"
check_layer e2e "${ART_BASE_STAGE}/e2e/results.json" "TC-F2-36-01,TC-F2-36-02,TC-F2-36-03,TC-F2-36-04,TC-F2-37-01,TC-F2-37-02,TC-F2-37-03,TC-F2-37-04"
check_layer recovery "${ART_BASE_STAGE}/recovery/results.json" "TC-F2-38-01,TC-F2-38-02,TC-F2-38-03,TC-F2-38-04"

node -e '
  const fs = require("node:fs");
  const [dir, started, commit, missing, failed, contractCode, base] = process.argv.slice(1);
  const readLayer = (p) => { try { return JSON.parse(fs.readFileSync(p, "utf8")); } catch { return null; } };
  const local = readLayer(base + "/local/results.json");
  const e2e = readLayer(base + "/e2e/results.json");
  const recovery = readLayer(base + "/recovery/results.json");
  // G1–G7（§12.5）：本地证据可证的 Gate 给 PASS；跨层缺证据如实 NOT_RUN。
  const gates = {
    G1: local && local.exitCode === 0 ? "PASS" : "NOT_RUN",
    G2: e2e && e2e.exitCode === 0 ? "PASS" : "NOT_RUN",
    G3: e2e && e2e.exitCode === 0 ? "PASS" : "NOT_RUN",
    G4: e2e && e2e.exitCode === 0 ? "PASS" : "NOT_RUN",
    G5: e2e && e2e.exitCode === 0 ? "PASS" : "NOT_RUN",
    G6: recovery && recovery.exitCode === 0 ? "PASS" : "NOT_RUN",
    G7: Number(contractCode) === 0 ? "PASS" : "NOT_RUN",
  };
  const missingList = missing ? missing.split(" ") : [];
  const failedList = failed ? failed.split(" ") : [];
  const ok = missingList.length === 0 && failedList.length === 0;
  fs.writeFileSync(`${dir}/summary.json`, JSON.stringify({
    taskBook: "107-fix-2 v1.0.0", stage: "all", card: "C107F2-39/C107F2-40",
    exitCode: ok ? 0 : 1, commit, startedAt: started, finishedAt: new Date().toISOString(),
    layers: { local, e2e, recovery }, gates,
    live: "NOT_RUN（未授权真实商业 Provider；本地结果不得表述为全平台全量通过）",
    verdict: ok
      ? "107-fix-2 本地交付验收 LOCAL_PASS（LOCAL 全层通过；LIVE NOT_RUN）"
      : "LOCAL_INCOMPLETE",
    missing: missingList, failed: failedList,
  }, null, 2) + "\n");
' "${ART_DIR}" "${STARTED_AT}" "${COMMIT}" "${MISSING[*]:-}" "${FAILED[*]:-}" "${contract_code}" "${ART_BASE_STAGE}"

if [ "${#MISSING[@]}" -gt 0 ] || [ "${#FAILED[@]}" -gt 0 ]; then
  printf 'FAIL stage all：\n'
  [ "${#MISSING[@]}" -gt 0 ] && printf '  缺项：\n' && printf '    - %s\n' "${MISSING[@]}"
  [ "${#FAILED[@]}" -gt 0 ] && printf '  失败层：\n' && printf '    - %s\n' "${FAILED[@]}"
  printf '汇总：%s/summary.json（不宣称全任务 VERIFIED）\n' "${ART_DIR}"
  exit 1
fi
printf 'PASS stage all：%s/summary.json（LOCAL_PASS；LIVE NOT_RUN——表述限定「107-fix-2本地交付验收」）\n' "${ART_DIR}"
exit 0
