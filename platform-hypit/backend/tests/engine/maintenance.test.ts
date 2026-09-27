// maintenance.test.ts — 107-4 C09/C12：backup.sh 的 K06 drain 协议端点。
//
// /internal/v1/maintenance/enter|exit 必须：enter 排空在途命令后进入维护窗
// （重复 enter=409）；维护期内新的副作用提交（commands/resources POST）拒绝
// 503、读路径与 /healthz 照常；exit 恢复接收。鉴权与 /internal/* 其余端点同闸。
process.env.HYPIT_STATE_HOME ??= join(import.meta.dirname, "../../../../data/hypit/runner-state");

import { spawn } from "node:child_process";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { setTimeout as delay } from "node:timers/promises";
import assert from "node:assert/strict";
import test from "node:test";

const backendRoot = join(import.meta.dirname, "../..");
const generatedRoot = join(backendRoot, "../.generated/hypit");
const dataRoot = mkdtempSync(join(tmpdir(), "hypit-maint-"));
const token = "maintenance-test-token-0123456789abcdef";
const port = 9254;

test("maintenance window drains, refuses new side effects, and exits cleanly", { timeout: 120_000 }, async (t) => {
  const child = spawn(process.execPath, ["--import", "tsx", "src/main.mjs"], {
    cwd: backendRoot,
    env: {
      ...process.env,
      HYPIT_BACKEND_PORT: String(port),
      HYPIT_INTERNAL_TOKEN: token,
      HYPIT_DATA_ROOT: join(dataRoot, "host"),
      HYPIT_GENERATED_ROOT: generatedRoot,
    },
    stdio: ["ignore", "pipe", "pipe"],
  });
  t.after(() => {
    child.kill("SIGTERM");
    rmSync(dataRoot, { recursive: true, force: true });
  });

  let ready = false;
  for (let attempt = 0; attempt < 400 && !ready; attempt += 1) {
    ready = await fetch(`http://127.0.0.1:${port}/healthz`)
      .then((response) => response.ok)
      .catch(() => false);
    if (!ready) await delay(250);
  }
  assert.ok(ready, "server did not become healthy");

  const auth = { "content-type": "application/json", authorization: `Bearer ${token}` };

  // 维护端点与其他 /internal/* 端点同鉴权闸：缺 token 一律 401。
  const noToken = await fetch(`http://127.0.0.1:${port}/internal/v1/maintenance/enter`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: "{}",
  });
  assert.equal(noToken.status, 401, "maintenance enter without token must be 401");

  // enter：空库排空立即完成，报告 activeCommands=0。
  const enter = await fetch(`http://127.0.0.1:${port}/internal/v1/maintenance/enter`, {
    method: "POST",
    headers: auth,
    body: JSON.stringify({ reason: "backup" }),
  });
  assert.equal(enter.status, 200, "enter must be accepted");
  assert.deepEqual(await enter.json(), { ok: true, activeCommands: 0 });

  // 重复 enter：409（backup.sh 对 200|409 均视为已进入维护）。
  const reenter = await fetch(`http://127.0.0.1:${port}/internal/v1/maintenance/enter`, {
    method: "POST",
    headers: auth,
    body: "{}",
  });
  assert.equal(reenter.status, 409, "re-enter must be 409");

  // 维护期内：新命令与资源 ingest 拒绝 503；读路径（含 /healthz 与命令查询）照常。
  const refused = await fetch(`http://127.0.0.1:${port}/internal/v1/commands`, {
    method: "POST",
    headers: auth,
    body: JSON.stringify({ commandId: "m1", kind: "status", payload: {} }),
  });
  assert.equal(refused.status, 503, "new commands must be refused during maintenance");

  const refusedIngest = await fetch(`http://127.0.0.1:${port}/internal/v1/resources`, {
    method: "POST",
    headers: auth,
    body: "{}",
  });
  assert.equal(refusedIngest.status, 503, "resource ingest must be refused during maintenance");

  const healthDuring = await fetch(`http://127.0.0.1:${port}/healthz`);
  assert.ok(healthDuring.ok, "/healthz must stay available during maintenance");

  const readDuring = await fetch(`http://127.0.0.1:${port}/internal/v1/commands/none-such`, {
    headers: { authorization: `Bearer ${token}` },
  });
  assert.equal(readDuring.status, 404, "read path must still route (404 for unknown id, not 503)");

  // exit 后恢复接收：status 命令重新可提交。
  const exit = await fetch(`http://127.0.0.1:${port}/internal/v1/maintenance/exit`, {
    method: "POST",
    headers: auth,
    body: "{}",
  });
  assert.equal(exit.status, 200, "exit must be accepted");

  const accepted = await fetch(`http://127.0.0.1:${port}/internal/v1/commands`, {
    method: "POST",
    headers: auth,
    body: JSON.stringify({ commandId: "m2", kind: "status", payload: {} }),
  });
  assert.equal(accepted.status, 200, "commands must be accepted after exit");
  const command = await accepted.json() as { state: string };
  assert.equal(command.state, "succeeded");
});
