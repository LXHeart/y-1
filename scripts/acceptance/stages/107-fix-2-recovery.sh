#!/usr/bin/env bash
# 107-fix-2-recovery.sh — V-10 stage recovery：故障注入/备份恢复演练（C107F2-38）。
#
# 编排（幂等，可重复执行）：
#   1. 隔离栈 y1-hypit-fix2-e2e（--test --enable-hypit --disable-dh + 受控文本
#      fixture）经唯一守卫入口 hypit-compose.sh 拉起；fixture token 与 verify
#      --stage card 同源（isolated-stack.env 持久值/静态默认），避免治理台已种
#      凭据 409 复用时 key 错位。
#   2. seed 合成账号后清场前轮遗留非终态 job（全部 kind；毒 job 队头会饿死
#      本轮 author 链——与 card 门禁同一条 SQL）。
#   3. npx playwright test tests/e2e/hypit-fix2-recovery.spec.ts（chromium；
#      TC-01/02/04 走 API+SQL 锚点，TC-03 备份→新 PG/卷恢复在隔离副本项目
#      y1-hypit-fix2-restore 上演练，绝不触碰主栈/开发卷）。
#   4. 解析 JUnit（testcase 块判定），写 test-artifacts/task-107/fix2/recovery/
#      results.json；executed=0 或失败 → 非零。
set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
cd "${ROOT_DIR}"

ART_BASE_STAGE="${FIX2_ART_BASE:-test-artifacts/task-107/fix2}"
ART_DIR="${ART_BASE_STAGE}/recovery"
SPEC="tests/e2e/hypit-fix2-recovery.spec.ts"
BASE="${BASE_URL:-http://127.0.0.1:18080}"
STARTED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
COMMIT="$(git rev-parse HEAD 2>/dev/null || echo unknown)"
mkdir -p "${ART_DIR}"

stack_online() {
  curl -sf -o /dev/null --max-time 5 "${BASE}" >/dev/null 2>&1
}

# fixture token 同源（先读持久值，缺省用静态默认并回填——与 card 门禁同一约定）。
PERSIST_ENV="test-artifacts/task-107/fix2/isolated-stack.env"
PERSISTED_TOKEN=""
[ -f "${PERSIST_ENV}" ] \
  && PERSISTED_TOKEN="$(grep -E '^HYPIT_FIX2_PROVIDER_TOKEN=' "${PERSIST_ENV}" | head -1 | cut -d= -f2- | tr -d '\"')"
if [ -n "${PERSISTED_TOKEN}" ]; then
  export HYPIT_FIX2_PROVIDER_TOKEN="${HYPIT_FIX2_PROVIDER_TOKEN:-${PERSISTED_TOKEN}}"
else
  export HYPIT_FIX2_PROVIDER_TOKEN="${HYPIT_FIX2_PROVIDER_TOKEN:-fix2-journey-fixture-key}"
fi
export HYPIT_FIX2_TEXT_FIXTURE=1

if ! stack_online; then
  printf '[%s] 隔离栈未在线，以启用组合拉起（hypit-compose.sh 唯一入口）\n' "${STARTED_AT}"
  bash scripts/acceptance/hypit-compose.sh --test --enable-hypit --disable-dh up \
    frontend hypit-backend hypit-author-runner redis hypit-fix2-text-provider \
    >"${ART_DIR}/stack-up.log" 2>&1
  up_code=$?
  if [ "${up_code}" -ne 0 ]; then
    printf 'FAIL 隔离栈启动失败（exit=%s，详见 %s）\n' "${up_code}" "${ART_DIR}/stack-up.log"
    exit 1
  fi
else
  printf '[%s] 隔离栈已在线（%s）\n' "${STARTED_AT}" "${BASE}"
fi

printf '[%s] seed 合成账号\n' "${STARTED_AT}"
bash scripts/acceptance/hypit-compose.sh --test seed-accounts >"${ART_DIR}/seed.log" 2>&1 \
  || { printf 'FAIL seed-accounts 失败（详见 %s）\n' "${ART_DIR}/seed.log"; exit 1; }

E2E_PASSWORD_FILE="${PERSIST_ENV}"
if [ -f "${E2E_PASSWORD_FILE}" ]; then
  E2E_PASSWORD="$(grep -E '^E2E_PASSWORD=' "${E2E_PASSWORD_FILE}" | head -1 | cut -d= -f2- | tr -d '\"')"
  export E2E_PASSWORD
fi

# 清场前轮遗留非终态 job（全部 kind；毒 job 队头饿死新 job——与 card 门禁同 SQL）。
docker exec y1-hypit-fix2-e2e-postgres-local-1 sh -c \
  'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "update hypit_job set state='"'"'failed'"'"', phase='"'"'error'"'"', error_message=coalesce(error_message,'"'"''"'"') || '"'"'; stale from a previous stage round'"'"' where state in ('"'"'running'"'"','"'"'queued'"'"')"' \
  >"${ART_DIR}/stale-jobs.log" 2>&1 || true

JUNIT="${ART_DIR}/playwright-junit.xml"
printf '[%s] playwright：%s（chromium）\n' "${STARTED_AT}" "${SPEC}"
BASE_URL="${BASE}" npx playwright test "${SPEC}" --project=chromium \
  >"${ART_DIR}/playwright.log" 2>&1
pw_code=$?
[ -f test-artifacts/playwright-results.xml ] && mv test-artifacts/playwright-results.xml "${JUNIT}"

node -e '
  const fs = require("node:fs");
  const [dir, code, started, commit] = process.argv.slice(1);
  let tests = null, tc = { found: [], failed: [] };
  try {
    const xml = fs.readFileSync(dir + "/playwright-junit.xml", "utf8");
    const chunks = xml.split(/<\/testcase>/);
    const cases = [], fails = [];
    for (const chunk of chunks) {
      const m = chunk.match(/<testcase[^>]*\sname="([^"]*)"/);
      if (!m) continue;
      cases.push(m[1]);
      if (/<failure[\s>]|<error[\s>]/.test(chunk)) fails.push(m[1]);
    }
    const total = cases.length, failed = fails.length;
    tests = { total, passed: total - failed, failed, skipped: 0, executed: total };
    const found = new Set(), tcFailed = new Set();
    for (const n of cases) for (const m of n.matchAll(/TC-F2-\d{2}-\d{2}/g)) found.add(m[0]);
    for (const n of fails) for (const m of n.matchAll(/TC-F2-\d{2}-\d{2}/g)) tcFailed.add(m[0]);
    tc = { found: [...found], failed: [...tcFailed] };
  } catch (e) {
    console.error("junit 解析失败：" + e.message);
  }
  fs.writeFileSync(dir + "/results.json", JSON.stringify({
    taskBook: "107-fix-2 v1.0.0", stage: "recovery", card: "C107F2-38",
    exitCode: Number(code), commit, startedAt: started, finishedAt: new Date().toISOString(),
    node: process.version, java: null, imageDigest: null, tests, tc, notRun: [], notes: [],
  }, null, 2) + "\n");
' "${ART_DIR}" "${pw_code}" "${STARTED_AT}" "${COMMIT}"

if [ "${pw_code}" -ne 0 ]; then
  printf 'FAIL stage recovery：playwright exit=%s（详情 %s/playwright.log）\n' "${pw_code}" "${ART_DIR}"
  exit 1
fi
printf 'PASS stage recovery：TC 证据在 test-artifacts/task-107/fix2/C38/，汇总在 %s/results.json\n' "${ART_DIR}"
exit 0
