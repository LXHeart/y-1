#!/usr/bin/env bash
# 107-fix-2-all.sh — V-11 stage all：全层汇总与最终集成出口（C107F2-39/C107F2-40）。
#
# 判定原则（§12.3 V-11 / §12.5）：
#   - 只解析各层实际产物（results.json + 原始 JUnit），不以日志关键词或「上次跑过」
#     代替；任一必需层缺产物/exitCode≠0/executed=0/存在失败/skip/todo → 非零并逐项
#     列出缺项。e2e 层逐引擎复核（缺引擎、引擎零执行、results.json 与原始 JUnit
#     不一致=旧结果重贴，均拒绝）。
#   - G1–G7 由 W053 deriveFix2Gates 按验证后层结果与 TC 目标子集推导——不按某层
#     exit0 粗赋 G2～G5；无法证明的 Gate 如实 NOT_RUN。
#   - LIVE（真实商业 Provider）未授权恒 NOT_RUN：本地全绿只能表述为
#     「107-fix-2 本地交付验收 LOCAL_PASS」，绝无全平台全量通过表述。
set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
cd "${ROOT_DIR}"

RESULTS_PARSER="scripts/acceptance/hypit-fix3-results.mjs"
ART_BASE_STAGE="${FIX2_ART_BASE:-test-artifacts/task-107/fix2}"
ART_DIR="${ART_BASE_STAGE}/all"
STARTED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
COMMIT="$(git rev-parse HEAD 2>/dev/null || echo unknown)"
mkdir -p "${ART_DIR}"

declare -a MISSING=()
declare -a FAILED=()

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

# 分层判定：W053 layer 子命令按真实产物核验（exitCode/计数/期望TC/e2e逐引擎与
# 原始JUnit一致性）；问题清单逐项进 FAILED。
LOCAL_EXPECT="TC-F2-08-01,TC-F2-08-02,TC-F2-08-03,TC-F2-08-04"
E2E_EXPECT="TC-F2-36-01,TC-F2-36-02,TC-F2-36-03,TC-F2-36-04,TC-F2-37-01,TC-F2-37-02,TC-F2-37-03,TC-F2-37-04"
RECOVERY_EXPECT="TC-F2-38-01,TC-F2-38-02,TC-F2-38-03,TC-F2-38-04"

for spec in "local:${LOCAL_EXPECT}" "e2e:${E2E_EXPECT}" "recovery:${RECOVERY_EXPECT}"; do
  layer="${spec%%:*}"
  expect="${spec#*:}"
  path="${ART_BASE_STAGE}/${layer}/results.json"
  if [ ! -f "${path}" ]; then
    MISSING+=("${layer}:${path}")
    continue
  fi
  layer_args=(--file "${path}" --layer "${layer}" --expect "${expect}")
  if [ "${layer}" = "e2e" ]; then layer_args+=(--e2e-dir "${ART_BASE_STAGE}/e2e"); fi
  layer_code=0
  printf '[%s] layer 门禁 %s\n' "${STARTED_AT}" "${layer}"
  node "${RESULTS_PARSER}" layer "${layer_args[@]}" || layer_code=$?
  [ "${layer_code}" -ne 0 ] && FAILED+=("${layer}(layer 门禁未过，问题清单见上方输出)")
done

node "${RESULTS_PARSER}" all-summary --base "${ART_BASE_STAGE}" \
  --contract-code "${contract_code}" --commit "${COMMIT}" --started "${STARTED_AT}" \
  --missing "${MISSING[*]:-}" --failed "${FAILED[*]:-}" >>"${ART_DIR}/summary.log" 2>&1
summary_code=$?

if [ "${#MISSING[@]}" -gt 0 ] || [ "${#FAILED[@]}" -gt 0 ] || [ "${summary_code}" -ne 0 ]; then
  printf 'FAIL stage all：\n'
  [ "${#MISSING[@]}" -gt 0 ] && printf '  缺项：\n' && printf '    - %s\n' "${MISSING[@]}"
  [ "${#FAILED[@]}" -gt 0 ] && printf '  失败层：\n' && printf '    - %s\n' "${FAILED[@]}"
  [ "${summary_code}" -ne 0 ] && printf '  summary 推导失败（%s/summary.log）\n' "${ART_DIR}"
  printf '汇总：%s/summary.json（不宣称全任务 VERIFIED）\n' "${ART_DIR}"
  exit 1
fi
printf 'PASS stage all：%s/summary.json（LOCAL_PASS；LIVE NOT_RUN——表述限定「107-fix-2本地交付验收」）\n' "${ART_DIR}"
exit 0
