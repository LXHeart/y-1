// fix2-c04.test.ts — 107-fix-2 C107F2-04：独立无网络 runner 接通（F02）。
//
// 拓扑（真实 Docker，deploy/hypit/compose.runner.yml，专用工程 fix2-c04-runner）：
//   hypit-backend（broker，publish 127.0.0.1:19242）── /sockets + /slots 卷 ──
//   hypit-author-runner（network:none/非root/只读根/全弃权，常驻 daemon）。
//
// TC-F2-04-01 broker 发 check → 作者 PID 在 runner 容器（结果 __runner 标记 +
//            broker healthz authorProcessSpawnCount===0）。
// TC-F2-04-02 三个单因子反例（兄弟 slot / 敏感路径 / 外网）在 runner 容器的
//            一次性子进程作用域内分别被 Node permission 拒绝，输出不含秘密，
//            且不是编译语法错误。
// TC-F2-04-03 执行中杀死 runner 子进程 → 旧调用明确失败、新请求可执行（容量不泄漏）。
// TC-F2-04-04 runner 容器停止 → check 失败、broker spawn 计数恒 0、不假成功。
import { execFileSync, spawn, spawnSync } from "node:child_process";
import { mkdtempSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import assert from "node:assert/strict";
import test from "node:test";

const repoRoot = resolve(import.meta.dirname, "../../../..");
const PROJECT = "fix2-c04-runner";
const BROKER_BASE = "http://127.0.0.1:19242";
const TOKEN = "fix2-c04-internal-token-0123456789abcdef";  // secret-scan: allow
const COMPOSE_FILE = "deploy/hypit/compose.runner.yml";
const EVIDENCE = join(repoRoot, "test-artifacts/task-107/fix2/C04");
import { mkdirSync, writeFileSync } from "node:fs";
mkdirSync(EVIDENCE, { recursive: true });

function dc(args: string[], timeoutMs = 600_000): string {
  return execFileSync("docker", ["compose", "-p", PROJECT, "-f", COMPOSE_FILE, ...args], {
    cwd: repoRoot, encoding: "utf8", timeout: timeoutMs,
    env: {
      ...process.env,
      HYPIT_INTERNAL_TOKEN: TOKEN,
      HYPIT_STUDIO_TICKET_SECRET: "fix2-c04-ticket-secret-0123456789ab",  // secret-scan: allow
      HYPIT_BROKER_HOST_PORT: "19242",
    },
  });
}

function brokerExec(script: string, timeoutMs = 60_000): string {
  const cid = dc(["ps", "-q", "hypit-backend"], 30_000).trim();
  assert.ok(cid.length > 0, "broker 容器应在运行");
  return execFileSync("docker", ["exec", cid, "node", "--import", "tsx", "-e", script], {
    cwd: repoRoot, encoding: "utf8", timeout: timeoutMs,
    env: { ...process.env },
  });
}

function runnerExec(command: string, timeoutMs = 60_000): string {
  const cid = dc(["ps", "-q", "hypit-author-runner"], 30_000).trim();
  assert.ok(cid.length > 0, "runner 容器应在运行");
  return execFileSync("docker", ["exec", cid, "sh", "-c", command], { cwd: repoRoot, encoding: "utf8", timeout: timeoutMs });
}

/** broker 拒绝超大请求体（>1MB）后会关闭该 keep-alive 连接，复用方的下一条
 *  fetch 可能拿到「other side closed」——网络层失败重试一次（业务 4xx/5xx 不重试）。 */
async function fetchBroker(url: string, init: RequestInit): Promise<Response> {
  try {
    return await fetch(url, init);
  } catch {
    await new Promise((resolve) => setTimeout(resolve, 1500));
    return await fetch(url, init);
  }
}

async function postCommand(commandId: string, kind: string, payload: unknown): Promise<{ status: number; body: unknown }> {
  const res = await fetchBroker(`${BROKER_BASE}/internal/v1/commands`, {
    method: "POST",
    headers: { "content-type": "application/json", authorization: `Bearer ${TOKEN}` },
    body: JSON.stringify({ commandId, kind, payload }),
  });
  return { status: res.status, body: await res.json().catch(() => null) };
}

async function getCommand(commandId: string): Promise<{ status: number; body: Record<string, unknown> | null }> {
  const res = await fetchBroker(`${BROKER_BASE}/internal/v1/commands/${commandId}`, {
    headers: { authorization: `Bearer ${TOKEN}` },
  });
  return { status: res.status, body: await res.json().catch(() => null) };
}

async function brokerHealthz(): Promise<any> {
  const res = await fetchBroker(`${BROKER_BASE}/healthz`, {});
  assert.equal(res.status, 200);
  return await res.json();
}

/** 通过 internal API 建模板工程并跑 check；返回命令终态与结果。 */
async function provisionAndCheck(projectId: string, commandId: string): Promise<any> {
  const provision = await postCommand(`${commandId}-prov`, "workspace.provision", {
    projectId, template: true,
  });
  assert.equal(provision.status, 200, `provision 失败：${JSON.stringify(provision.body)}`);
  const check = await postCommand(commandId, "check", { projectId, entryFile: "main.svml" });
  return { check, command: await getCommand(commandId) };
}

test("TC-F2-04-01 作者执行归属 runner 容器；broker 内作者执行计数为 0", { timeout: 15 * 60_000 }, async () => {
  dc(["down", "-v", "--remove-orphans"], 120_000);
  dc(["up", "-d", "--build", "--wait", "--wait-timeout", "600"]);
  // broker 容器与 runner 容器都在。
  assert.ok(dc(["ps", "-q", "hypit-backend"], 30_000).trim().length > 0);
  assert.ok(dc(["ps", "-q", "hypit-author-runner"], 30_000).trim().length > 0);
  // runner 无网络（D-04 拓扑事实）：network=none 命名空间在现代内核仍含
  // tunl0/gre0/sit0 等零流量内核虚拟设备（行数断言不成立）。真实判据：
  // 非 lo 接口零收发字节 + 路由表为空（无任何可达性表面）。
  const netOk = runnerExec(
    "awk 'NR>2 && $1 != \"lo:\" && ($2 + 0) > 0 { bad = 1 } END { exit bad }' /proc/net/dev" +
    " && [ \"$(wc -l < /proc/net/route)\" -le 1 ] && echo net-none-ok",
  );
  assert.ok(netOk.includes("net-none-ok"), `runner 应 network:none（非 lo 接口须零流量且无路由）：${netOk}`);

  const projectId = "22222222-2222-4222-8222-222222222204";
  const { check, command } = await provisionAndCheck(projectId, "cmd-fix2-c04-01-check");
  writeFileSync(join(EVIDENCE, "tc01-command.json"), JSON.stringify(command.body, null, 2));
  assert.equal(check.status, 200, `check 命令应被受理：${JSON.stringify(check.body)}`);
  // check 必须真正经 runner 执行（结果 __runner 标记；业务成败都带标记）。
  const marker = check.body?.result?.__runner ?? command.body?.result?.__runner;
  assert.ok(marker, `结果应含 __runner：${JSON.stringify(check.body).slice(0, 400)}`);
  assert.equal(marker.container, "runner", "作者执行必须发生在 runner 容器");
  assert.ok(Number.isInteger(marker.pid) && marker.pid > 1, "须带回 runner 内子进程 PID");
  writeFileSync(join(EVIDENCE, "tc01-runner-marker.txt"), JSON.stringify(marker));
  // broker 侧零作者执行进程。
  const health = await brokerHealthz();
  assert.equal(health.runner.authorProcessSpawnCount, 0, "broker 内作者代码执行计数必须为 0");
  writeFileSync(join(EVIDENCE, "tc01-healthz.json"), JSON.stringify(health, null, 2));
});

test("TC-F2-04-02 兄弟 slot/敏感路径/外网三因子在 runner 子进程作用域分别被拒", { timeout: 5 * 60_000 }, () => {
  // 单因子反例：按 daemon 相同的 permission 收敛面在 runner 容器内起一次性子进程
  //（复现 daemon.spawnOneShot 的 flags——引擎/源码只读、slot 外不可读、无 net 权限）。
  const probe = (attemptJs: string): { out: string; code: number } => {
    const script = `
      const { spawnSync } = require("node:child_process");
      const slot = "/slots/probe-slot";
      require("node:fs").mkdirSync(slot + "/output", { recursive: true });
      const r = spawnSync(process.execPath, [
        "--permission", "--allow-fs-read=/app", "--allow-fs-read=" + slot,
        "--allow-fs-write=" + slot + "/output", "-e", ${JSON.stringify(attemptJs)},
      ], { encoding: "utf8" });
      process.stdout.write((r.stdout || "") + (r.stderr || ""));
      process.exit(r.status ?? 0);
    `;
    // 嵌套 -e 的多层引号经 sh -c 再经 spawnSync 会破坏 \\\" 转义（V8 "Expected
    // unicode escape"）。base64 经 stdin 喂 node，传输层零转义。
    const b64 = Buffer.from(script, "utf8").toString("base64");
    const out = runnerExec(`echo ${b64} | base64 -d | node -`);
    return { out, code: 0 };
  };
  // 因子1：读兄弟 slot。
  runnerExec("mkdir -p /slots/other-slot && echo sibling-secret-value > /slots/other-slot/secret.txt");
  const sibling = probe(`try { require("node:fs").readFileSync("/slots/other-slot/secret.txt","utf8"); console.log("PROBE_SUCCEEDED") } catch (e) { console.log("DENIED:" + e.code) }`);
  assert.ok(!sibling.out.includes("PROBE_SUCCEEDED"), "兄弟 slot 读取必须被拒");
  assert.match(sibling.out, /DENIED|ERR_ACCESS_DENIED|permission/i, `应报权限拒绝：${sibling.out.slice(0, 300)}`);
  assert.ok(!sibling.out.includes("sibling-secret-value"), "输出不得泄漏兄弟 slot 秘密内容");
  writeFileSync(join(EVIDENCE, "tc02-sibling.txt"), sibling.out);

  // 因子2：读敏感路径（broker 数据/journal 不挂载 + permission 双拒）。
  const secret = probe(`try { require("node:fs").readFileSync("/data/hypit/bridge.sqlite"); console.log("PROBE_SUCCEEDED") } catch (e) { console.log("DENIED:" + e.code) }`);
  assert.ok(!secret.out.includes("PROBE_SUCCEEDED"), "敏感路径读取必须被拒");
  assert.match(secret.out, /DENIED|ERR_ACCESS_DENIED|ENOENT|permission/i);
  writeFileSync(join(EVIDENCE, "tc02-secret.txt"), secret.out);

  // 因子3：外网/本地回环连接。
  const net = probe(`try { const s = require("node:net").connect(9240, "127.0.0.1"); s.on("connect", () => { console.log("PROBE_SUCCEEDED"); process.exit(0); }); s.on("error", (e) => console.log("DENIED:" + e.code)); setTimeout(() => { console.log("DENIED:timeout"); process.exit(0); }, 3000); } catch (e) { console.log("DENIED:" + e.code) }`);
  assert.ok(!net.out.includes("PROBE_SUCCEEDED"), "网络连接必须被拒");
  assert.match(net.out, /DENIED/i);
  writeFileSync(join(EVIDENCE, "tc02-net.txt"), net.out);
  // 清理探针 slot。
  runnerExec("rm -rf /slots/probe-slot /slots/other-slot");
});

test("TC-F2-04-03 执行中杀死 runner 子进程 → 旧调用失败、新请求可执行（容量不泄漏）", { timeout: 5 * 60_000 }, async () => {
  // 大工作区：足够让 plan 跑数秒，制造可杀窗口。
  const projectId = "22222222-2222-4222-8222-222222222205";
  const prov = await postCommand("cmd-c04-03-prov", "workspace.provision", {
    projectId, template: true,
  });
  assert.equal(prov.status, 200);
  // 放大版 svml（万元素级）延长 runner 执行。必须走 workspace.apply 合法通道：
  // 直写 work 目录会被引擎的 manifest 漂移防护拒绝（"workspace drifted from
  // head manifest; refusing to snapshot"——那是产品正确行为，不是测试造数法）。
  // 内容须为合法 svml：带 <?svml using?> 序言并重复模板里已知合法的
  // spatial:Canvas 元素（自造裸元素会被引擎秒拒）；总量压在 broker 1MB 请求体
  // 上限内（10000 行 ≈ 600KB）。
  const headRevision = Number((prov.body as { result?: { head?: { revision?: number } } } | null)?.result?.head?.revision ?? 1);
  const canvases = Array.from({ length: 10000 }, (_, i) => `  <spatial:Canvas id="bulk-${i}" width="540" height="960"/>`).join("\n");
  const apply = await postCommand("cmd-c04-03-apply", "workspace.apply", {
    projectId,
    baseRevision: headRevision,
    applyMode: "save",
    changes: [{
      path: "big.svml",
      action: "put",
      content: "<?svml using=\"@hypit/markup@1\"?>\n<svml>\n"
        + "  <import as=\"spatial\" from=\"@hypit/spatial@1\"/>\n"
        + canvases
        + "\n</svml>\n",
    }],
  });
  assert.equal(apply.status, 200, `apply 应成功：${JSON.stringify(apply.body).slice(0, 300)}`);

  // 执行中击杀不与命令快慢赛跑，用「探针先连后发」：探针进程先建立 daemon
  // 连接并进入 50ms status 轮询，然后才投递 plan——daemon 容量 1，
  // status.busy===true 即本命令的一次性子进程已 spawn（busy 覆盖整个子进程
  // 生命周期，含 tsx 冷启动 >800ms），busy 一出现立刻对 server.ts 子进程
  // SIGKILL（daemon 与子进程同为 uid 10001，同 uid 信号无需 capabilities）。
  // 不走 daemon cancel 帧：supervisor 发往 daemon 的 commandId 是自行生成的
  // （supervisor.ts:89），与 dispatcher 命令 id 不同，cancel 按 id 匹配不到。
  const runnerCid = dc(["ps", "-q", "hypit-author-runner"], 30_000).trim();
  assert.ok(runnerCid.length > 0, "runner 容器应在运行");
  const planId = "cmd-c04-03-plan";
  const killScript = `
    import { readdirSync, readFileSync } from "node:fs";
    import { RunnerClient, connectWithRetry } from "/app/src/runner/client.ts";
    const client = new RunnerClient({ socketPath: "/sockets/runner.sock", frameLimitBytes: 1048576, requestTimeoutMs: 5000 });
    await connectWithRetry(client, 20, 100);
    try {
      for (let i = 0; i < 240; i += 1) {
        const status = await client.request("busy-probe", "status", {});
        if (status?.busy === true) {
          for (const pid of readdirSync("/proc").filter((d) => /^\\d+$/.test(d))) {
            try {
              const cmdline = readFileSync("/proc/" + pid + "/cmdline", "utf8");
              if (cmdline.includes("src/runner/server.ts")) {
                process.kill(Number(pid), "SIGKILL");
                console.log("KILLED_PID " + pid);
                process.exit(0);
              }
            } catch { /* 目标可能已退出，继续扫 */ }
          }
          console.log("BUSY_BUT_NO_CHILD");
          process.exit(3);
        }
        await new Promise((r) => setTimeout(r, 50));
      }
      console.log("NEVER_BUSY");
      process.exit(2);
    } finally {
      client.close();
    }
  `;
  const killProbe = spawn("docker", ["exec", "-i", runnerCid, "node", "--import", "tsx", "-"], {
    cwd: repoRoot, stdio: ["pipe", "pipe", "pipe"],
  });
  let killOut = "";
  killProbe.stdout.on("data", (chunk) => { killOut += chunk; });
  killProbe.stderr.on("data", (chunk) => { killOut += chunk; });
  killProbe.stdin.end(killScript);
  const probeExit = new Promise<number>((resolveCode) => killProbe.once("exit", (code) => resolveCode(code ?? -1)));
  // 探针已连接进入轮询后再投递 plan（docker exec + tsx 冷启动 ~2s）。
  await new Promise((resolveDelay) => setTimeout(resolveDelay, 2500));
  const inFlight = postCommand(planId, "plan", { projectId, runFile: "big.svml" });
  const probeCode = await probeExit;
  assert.equal(probeCode, 0, `应在 busy 窗口内击杀 server.ts 子进程（exit=${probeCode}）：${killOut.slice(-300)}`);
  assert.match(killOut, /KILLED_PID \d+/, `击杀证据缺失：${killOut.slice(-300)}`);
  writeFileSync(join(EVIDENCE, "tc03-kill.txt"), killOut);
  const old = await inFlight;
  writeFileSync(join(EVIDENCE, "tc03-old.json"), JSON.stringify(old, null, 2));
  // 被杀调用必须明确失败（state=failed + 错误码），不能假成功。
  const oldState = (old.body as { state?: string } | null)?.state
    ?? ((await getCommand("cmd-c04-03-plan")).body as { state?: string } | null)?.state;
  assert.equal(oldState, "failed", `被杀调用应落 failed 终态：${JSON.stringify(old.body).slice(0, 300)}`);
  // 新请求可执行：daemon 容量回收。
  const next = await provisionAndCheck(projectId, "cmd-c04-03-check");
  writeFileSync(join(EVIDENCE, "tc03-new.json"), JSON.stringify(next, null, 2));
  assert.ok(next.check.body?.result?.__runner ?? next.command.body?.result?.__runner, "杀死后新请求应正常经 runner 执行");
});

test("TC-F2-04-04 runner 容器停止 → check 失败且 broker spawn 计数 0（不假成功）", { timeout: 5 * 60_000 }, async () => {
  dc(["stop", "hypit-author-runner"], 120_000);
  const projectId = "22222222-2222-4222-8222-222222222206";
  await postCommand("cmd-c04-04-prov", "workspace.provision", {
    projectId, template: true,
  });
  const check = await postCommand("cmd-c04-04-check", "check", { projectId, entryFile: "main.svml" });
  writeFileSync(join(EVIDENCE, "tc04-check.json"), JSON.stringify(check, null, 2));
  // 受理后必须落 failed 终态（不假成功；POST 对已记录失败命令返回 200+failed 形状）。
  const command = await getCommand("cmd-c04-04-check");
  assert.equal(command.body?.state, "failed", `runner 停止时 check 必须失败：${JSON.stringify(command.body).slice(0, 300)}`);
  assert.ok(command.body?.error, "失败需带错误信息");
  assert.match(JSON.stringify(command.body.error), /runner|daemon|socket|connect/i, "错误应指向 runner 不可用");
  const health = await brokerHealthz();
  assert.equal(health.runner.authorProcessSpawnCount, 0, "broker 不得回退本地执行（spawn 计数恒 0）");
  writeFileSync(join(EVIDENCE, "tc04-healthz.json"), JSON.stringify(health, null, 2));
  // 恢复 runner 供后续卡使用。
  dc(["start", "hypit-author-runner"], 120_000);
});

// 收尾：保留 fix2-c04-runner 栈在运行（后续 C08 纵向链路复用），不删卷。
