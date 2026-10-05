// sessions.test.ts — C107F2-19：broker 会话注册表绑定真实子进程句柄。
// 票据签发/核销已移 Java（V92 nonceHash CAS）；本表只持有进程绑定与生命周期。
// 同 Run 复用键（owner/project/run/revision/readOnly 全等）由 C107F2-20 收口。
import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { strict as assert } from "node:assert";
import test from "node:test";

import {
  activeStudioSessionCount, advanceStudioSessionRevision, closeAllStudioSessions,
  closeStudioSession, registerStudioSession, requireStudioSession, reapExpiredStudioSessions,
} from "../../src/studio/sessions.ts";
import type { StudioProcess } from "../../src/studio/launcher.ts";
import { DispatchError } from "../../src/commands/dispatcher.ts";

/** 不启动真实子进程的 launcher 桩：记录 stop 调用与启动失败注入点。 */
function fakeLauncher(options: { readonly fail?: boolean } = {}) {
  const stopped: string[] = [];
  const launch = async (input: { readonly port: number }): Promise<StudioProcess> => {
    if (options.fail) throw new DispatchError("studio_unavailable", "studio process exited before ready (code 1)");
    let stoppedFlag = false;
    return {
      pid: 4242 + input.port,
      port: input.port,
      stop: () => {
        stoppedFlag = true;
        stopped.push(String(input.port));
      },
      // 桩进程永不退出（真实子进程退出时会话才被回收）。
      exited: new Promise<void>(() => {}),
      stderrTail: [],
    } as unknown as StudioProcess;
  };
  return { launch, stopped };
}

function workspaceFixture(): { projectsRoot: string; projectId: string; cleanup: () => void } {
  const dir = mkdtempSync(join(tmpdir(), "hypit-studio-sessions-"));
  const projectId = "32222222-2222-4222-8222-222222222222";
  mkdirSync(join(dir, projectId, "work"), { recursive: true });
  writeFileSync(join(dir, projectId, "work", "main.svrun"), '<svrun version="1"></svrun>\n');
  return { projectsRoot: dir, projectId, cleanup: () => rmSync(dir, { recursive: true, force: true }) };
}

const BASE = { distributionRoot: "/generated", projectsRoot: "/projects" } as const;

test("registration validates ids/revisions and binds the child process", async () => {
  const ws = workspaceFixture();
  const { launch } = fakeLauncher();
  try {
    await assert.rejects(
      registerStudioSession(BASE, { sessionId: "short", projectId: ws.projectId, ownerAccountId: "acc-1", runFile: "main.svrun", revision: 1, readOnly: false }, launch),
      DispatchError,
      "sessionId must be schema-checked",
    );
    await assert.rejects(
      registerStudioSession(BASE, { sessionId: "sess-00000001", projectId: ws.projectId, ownerAccountId: "acc-1", runFile: "main.svrun", revision: 0, readOnly: false }, launch),
      DispatchError,
      "revision must be positive (V92 CHECK)",
    );
    const session = await registerStudioSession(
      BASE,
      { sessionId: "sess-00000001", projectId: ws.projectId, ownerAccountId: "acc-1", runFile: "main.svrun", revision: 3, readOnly: false },
      launch,
    );
    assert.equal(session.revision, 3);
    assert.equal(requireStudioSession("sess-00000001").ownerAccountId, "acc-1");
    advanceStudioSessionRevision(session, 4);
    assert.equal(requireStudioSession("sess-00000001").revision, 4, "write-back advances the base revision");
    advanceStudioSessionRevision(session, 2);
    assert.equal(requireStudioSession("sess-00000001").revision, 4, "revision never regresses");
    // 同 id 重复注册拒绝（Java 的 PG 行唯一）。
    await assert.rejects(
      registerStudioSession(BASE, { sessionId: "sess-00000001", projectId: ws.projectId, ownerAccountId: "acc-1", runFile: "main.svrun", revision: 3, readOnly: false }, launch),
      DispatchError,
    );
    closeStudioSession("sess-00000001");
    assert.throws(() => requireStudioSession("sess-00000001"), DispatchError, "closed sessions are gone");
  } finally {
    closeAllStudioSessions();
    ws.cleanup();
  }
});

test("launch failure leaves no active session or zombie process (E03)", async () => {
  const ws = workspaceFixture();
  const { launch } = fakeLauncher({ fail: true });
  try {
    await assert.rejects(
      registerStudioSession(BASE, { sessionId: "sess-00000002", projectId: ws.projectId, ownerAccountId: "acc-1", runFile: "main.svrun", revision: 1, readOnly: true }, launch),
      (error: unknown) => error instanceof DispatchError && error.code === "studio_unavailable",
    );
    assert.equal(activeStudioSessionCount(), 0, "no active fake session survives a failed launch");
  } finally {
    closeAllStudioSessions();
    ws.cleanup();
  }
});

test("expired sessions are refused and reaped on access", async () => {
  const ws = workspaceFixture();
  const { launch } = fakeLauncher();
  try {
    const session = await registerStudioSession(
      BASE,
      { sessionId: "sess-00000003", projectId: ws.projectId, ownerAccountId: "acc-1", runFile: "main.svrun", revision: 1, readOnly: false, ttlSeconds: -1 },
      launch,
    );
    (session as { expiresAt: number }).expiresAt = Date.now() - 1_000;
    assert.throws(() => requireStudioSession("sess-00000003"), DispatchError, "expired sessions are refused");
    assert.equal(activeStudioSessionCount(), 0);
  } finally {
    closeAllStudioSessions();
    ws.cleanup();
  }
});


test("expired editor processes are reclaimed without another request; live sessions survive", async () => {
  const ws = workspaceFixture();
  const { launch, stopped } = fakeLauncher();
  try {
    const first = await registerStudioSession({ ...BASE, projectsRoot: ws.projectsRoot },
      { sessionId: "sess-reap0001", projectId: ws.projectId, ownerAccountId: "acc-1", runFile: "main.svrun", revision: 1, readOnly: false, ttlSeconds: 10 }, launch);
    await registerStudioSession({ ...BASE, projectsRoot: ws.projectsRoot },
      { sessionId: "sess-reap0002", projectId: ws.projectId, ownerAccountId: "acc-2", runFile: "main.svrun", revision: 1, readOnly: false, ttlSeconds: 60 }, launch);
    assert.equal(reapExpiredStudioSessions(first.expiresAt), 1);
    assert.equal(stopped.length, 1);
    assert.equal(requireStudioSession("sess-reap0002").state, "active");
    assert.equal(reapExpiredStudioSessions(first.expiresAt), 0);
  } finally { closeAllStudioSessions(); ws.cleanup(); }
});
