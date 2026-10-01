#!/usr/bin/env bash
# 107-fix-2-local.sh — V-08 stage local：真实 API 原生成片纵向链（C107F2-08）。
#
# 编排（幂等，可重复执行）：
#   1. 隔离栈 y1-hypit-fix2-e2e 在线检查（18080 三入口）；未在线则以启用组合
#      （--test --enable-hypit --enable-dh）经唯一入口 hypit-compose.sh 拉起。
#   2. wrapper seed-accounts 注入合成账号（OWNER_A=e2e-merchant / OWNER_B=e2e-cs）。
#   3. npx playwright test tests/e2e/hypit-fix2-api-render.spec.ts（chromium；
#      API-only，无 UI 断言）。四组 TC 的 ffprobe/三帧/hash 证据由 spec 落
#      test-artifacts/task-107/fix2/C08/。
#   4. 解析 JUnit XML 计数，写 test-artifacts/task-107/fix2/local/results.json。
#
# 无商业 Provider：栈内 AI 域名 qwen-e2e.invalid 恒不可达（TC-F2-08-04 断言零外部调用）。
set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
cd "${ROOT_DIR}"

ART_DIR="test-artifacts/task-107/fix2/local"
SPEC="tests/e2e/hypit-fix2-api-render.spec.ts"
BASE="${BASE_URL:-http://127.0.0.1:18080}"
STARTED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
COMMIT="$(git rev-parse HEAD 2>/dev/null || echo unknown)"

mkdir -p "${ART_DIR}"

stack_online() {
  curl -sf -o /dev/null --max-time 5 "${BASE}" >/dev/null 2>&1
}

if ! stack_online; then
  printf '[%s] 隔离栈未在线，以启用组合拉起（hypit-compose.sh 唯一入口）\n' "${STARTED_AT}"
  bash scripts/acceptance/hypit-compose.sh --test --enable-hypit --enable-dh up \
    >"${ART_DIR}/stack-up.log" 2>&1
  up_code=$?
  if [ "${up_code}" -ne 0 ]; then
    printf 'FAIL 隔离栈启动失败（exit=%s，详见 %s）\n' "${up_code}" "${ART_DIR}/stack-up.log"
    exit 1
  fi
else
  printf '[%s] 隔离栈已在线（%s）\n' "${STARTED_AT}" "${BASE}"
fi

# 口令桥：seed（wrapper 内 ${E2E_PASSWORD:-随机}）与 playwright 是两个进程——
# 不在 stage 层固定同值，seed 的随机口令只活在 wrapper 进程内，playwright 登录
# 必 401（V-08 首跑实录）。caller 显式传入时尊重调用方值。
export E2E_PASSWORD="${E2E_PASSWORD:-E2e!$(openssl rand -hex 16)}"

printf '[%s] seed 合成账号\n' "${STARTED_AT}"
bash scripts/acceptance/hypit-compose.sh --test seed-accounts >"${ART_DIR}/seed.log" 2>&1 \
  || { printf 'FAIL seed-accounts 失败（详见 %s）\n' "${ART_DIR}/seed.log"; exit 1; }

# 幂等口令对齐：e2e-seed 对已存在账号「口令不覆盖」（护共享库手工值），但隔离库
# 可能残留其他链路种的非 bcrypt 口令（97 字符哈希，登录恒 401——V-08 首跑实录）。
# 仅隔离工程域内按本次 E2E_PASSWORD 强制对齐 spec 登录账号。
DATABASE_URL="postgresql://${LOCAL_DB_USER:-grassland}:${LOCAL_DB_PASSWORD:-grassland}@127.0.0.1:${LOCAL_DB_PORT:-15432}/${LOCAL_DB_NAME:-grassland}" \
node --input-type=module -e '
import { createRequire } from "node:module";
const req = createRequire(import.meta.url);
const hash = req("bcryptjs").hashSync(process.env.E2E_PASSWORD, 10);
const { Client } = req("pg");
const client = new Client({ connectionString: process.env.DATABASE_URL });
await client.connect();
const r = await client.query(
  "UPDATE app_users SET password_hash = $1, status = '"'"'active'"'"' WHERE email = ANY($2)",
  [hash, ["e2e-merchant@test.local", "e2e-cs@test.local"]]);
console.log("password aligned:", r.rowCount);
await client.end();
' >"${ART_DIR}/password-align.log" 2>&1 \
  || { printf 'FAIL 口令对齐失败（详见 %s）\n' "${ART_DIR}/password-align.log"; exit 1; }

JUNIT="test-artifacts/task-107/fix2/local/playwright-junit.xml"
printf '[%s] playwright：%s（chromium，API-only）\n' "${STARTED_AT}" "${SPEC}"
BASE_URL="${BASE}" npx playwright test "${SPEC}" --project=chromium \
  >"${ART_DIR}/playwright.log" 2>&1
pw_code=$?
# junit 输出由 reporter 写入默认路径（config outputFile）；移动到产物目录。
[ -f test-artifacts/playwright-results.xml ] && mv test-artifacts/playwright-results.xml "${JUNIT}"

node -e '
  const fs = require("node:fs");
  const [dir, code, started, commit] = process.argv.slice(1);
  let tests = null, tc = { found: [], failed: [] };
  try {
    const xml = fs.readFileSync(dir + "/playwright-junit.xml", "utf8");
    // 按 testcase 块判定（与 verify-107-fix-2.sh 同法）：紧邻式正则漏检 system-out
    // 后置的 failure。
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
    const found = new Set();
    for (const n of cases) for (const m of n.matchAll(/TC-F2-\d{2}-\d{2}/g)) found.add(m[0]);
    const tcFailed = new Set();
    for (const n of fails) for (const m of n.matchAll(/TC-F2-\d{2}-\d{2}/g)) tcFailed.add(m[0]);
    tc = { found: [...found], failed: [...tcFailed] };
  } catch (e) {
    console.error("junit 解析失败：" + e.message);
  }
  fs.writeFileSync(dir + "/results.json", JSON.stringify({
    taskBook: "107-fix-2 v1.0.0", stage: "local", card: "C107F2-08",
    exitCode: Number(code), commit, startedAt: started, finishedAt: new Date().toISOString(),
    node: process.version, java: null, imageDigest: null, tests, tc, notRun: [], notes: [],
  }, null, 2) + "\n");
' "${ART_DIR}" "${pw_code}" "${STARTED_AT}" "${COMMIT}"

if [ "${pw_code}" -ne 0 ]; then
  printf 'FAIL stage local：playwright exit=%s（详情 %s/playwright.log）\n' "${pw_code}" "${ART_DIR}"
  exit 1
fi
printf 'PASS stage local：四组 TC 证据在 test-artifacts/task-107/fix2/C08/，汇总在 %s/results.json\n' "${ART_DIR}"
exit 0
