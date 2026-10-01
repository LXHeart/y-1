// @vitest-environment happy-dom
// fix2-c10.test.ts — C107F2-10（TC-F2-10-01～04）：统一文件 hash 契约与版本化
// 保存。mock HTTP 层用带 CAS 语义的内存文件仓（模拟 broker workspace.apply 的
// baseHash/revision 双闸），运行为真实 useHypitSource；不 mock 被测函数本身。
import { afterEach, describe, expect, test, vi } from 'vitest';
import { useHypitSource } from './useHypitSource';
import { readFile } from './hypit-api';

const PID = '44444444-4444-4444-8444-444444444444';
const PATH = 'scenes/main.js';

type Stored = { content: string; hash: string; revision: number };

function hashOf(content: string): string {
  return `hash-${content.length}-${content.slice(0, 8)}`;
}

function respond(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });
}

type CreateBody = {
  requestId: string;
  baseRevision: number;
  applyMode: string;
  changes: { path: string; action: string; content?: string; baseHash: string | null }[];
};

/**
 * 内存文件仓 + broker 替身：createChangeset 记录冻结载荷；apply 执行
 * baseHash/revision 双 CAS（不一致 409，一致则推进 head）。
 */
function installBroker(initial: Stored): {
  store: Stored;
  createBodies: CreateBody[];
  applyCalls: number;
  barrierApply: boolean;
  releaseBarrier: () => void;
} {
  const store = { ...initial };
  const createBodies: CreateBody[] = [];
  const state = { applyCalls: 0, barrierApply: false, releaseBarrier: () => {} };
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    const method = init?.method ?? 'GET';
    if (url.includes(`/projects/${PID}/file?`) && method === 'GET') {
      return respond(200, { success: true, data: { path: PATH, content: store.content, hash: store.hash, revision: store.revision } });
    }
    if (url.includes(`/projects/${PID}/files`) && method === 'GET') {
      return respond(200, { success: true, data: { revision: store.revision, manifestHash: 'mh', files: [{ path: PATH, sizeBytes: store.content.length, sha256: store.hash }] } });
    }
    if (url.endsWith(`/projects/${PID}/changesets`) && method === 'POST') {
      const body = JSON.parse(String(init?.body)) as CreateBody;
      createBodies.push(body);
      return respond(200, {
        success: true,
        data: { changesetId: `cs-${createBodies.length}`, baseRevision: body.baseRevision, applyMode: body.applyMode, checkStatus: 'passed', state: 'draft' },
      });
    }
    const applyMatch = url.match(/\/changesets\/([^/]+)\/apply$/);
    if (applyMatch && method === 'POST') {
      state.applyCalls += 1;
      const body = JSON.parse(String(init?.body)) as { requestId: string; baseRevision: number };
      const change = createBodies[createBodies.length - 1]?.changes[0];
      const casFail = body.baseRevision !== store.revision
        || (change?.action === 'put' && change.baseHash !== null && change.baseHash !== store.hash)
        || (change?.action === 'delete' && change.baseHash !== store.hash);
      const settle = (): Response => {
        if (casFail) {
          return respond(409, { success: false, error: '基线修订已过期，草稿已保留，请基于新 head 重新提交。', code: 'hypit_revision_conflict' });
        }
        store.content = change?.content ?? store.content;
        store.hash = hashOf(store.content);
        store.revision += 1;
        return respond(200, { success: true, data: { revision: store.revision, manifestHash: `mh-${store.revision}`, appliedPaths: [PATH] } });
      };
      if (state.barrierApply) {
        return new Promise<Response>((resolve) => {
          state.releaseBarrier = () => resolve(settle());
        });
      }
      return settle();
    }
    return respond(404, { success: false, error: 'nf', code: 'hypit_not_found' });
  }));
  return { store, createBodies, get applyCalls() { return state.applyCalls; }, set barrierApply(v: boolean) { state.barrierApply = v; }, releaseBarrier: () => state.releaseBarrier() };
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('C107F2-10 文件 hash 契约与版本化保存', () => {
  test('TC-F2-10-01 连续两次保存：baseHash 依次 A/B，最终磁盘 C', async () => {
    const broker = installBroker({ content: 'A', hash: 'hash-A', revision: 2 });
    const source = useHypitSource();
    await source.refresh(PID);
    await source.open(PID, PATH);
    expect(source.baseHash.value).toBe('hash-A');

    source.edit('B');
    expect(await source.save(PID, 'save')).toBe(true);
    expect(broker.createBodies[0]?.changes[0]?.baseHash).toBe('hash-A');

    source.edit('C');
    expect(await source.save(PID, 'save')).toBe(true);
    expect(broker.createBodies[1]?.changes[0]?.baseHash).toBe(hashOf('B'));

    // 最终磁盘 = C（以再次读取的权威内容为准）。
    const final = await readFile(PID, PATH);
    expect(final.content).toBe('C');
    expect(final.hash).toBe(hashOf('C'));
    expect(source.saveState.value).toBe('saved');
  });

  test('TC-F2-10-02 提交 B 在途时继续输入 C：savedContent=B、draft=C、dirty=true', async () => {
    const broker = installBroker({ content: 'A', hash: 'hash-A', revision: 5 });
    broker.barrierApply = true;
    const source = useHypitSource();
    await source.refresh(PID);
    await source.open(PID, PATH);
    source.edit('B');

    let released = false;
    const saving = source.save(PID, 'save').then((ok) => {
      released = true;
      return ok;
    });
    // 屏障暂停 apply：create 已带冻结的 B、apply 在途，本地继续输入 C。
    await vi.waitFor(() => expect(broker.createBodies.length).toBe(1));
    await vi.waitFor(() => expect(broker.applyCalls).toBe(1));
    expect(broker.createBodies[0]?.changes[0]?.content).toBe('B');
    source.edit('C');
    expect(released).toBe(false);

    broker.releaseBarrier();
    expect(await saving).toBe(true);
    await vi.waitFor(() => expect(released).toBe(true));
    expect(source.savedContent.value).toBe('B');
    expect(source.draft.value).toBe('C');
    expect(source.dirty.value).toBe(true);
    expect(source.saveState.value).toBe('dirty');
  });

  test('TC-F2-10-03 同 revision 两客户端：后者 409 保留草稿，无静默覆盖', async () => {
    const broker = installBroker({ content: 'A', hash: 'hash-A', revision: 2 });
    const source = useHypitSource();
    await source.refresh(PID);
    await source.open(PID, PATH);
    source.edit('B');
    // 客户端 A 先保存推进 head；客户端 B 仍持旧 revision/hash 提交。
    broker.store.content = 'A2';
    broker.store.hash = hashOf('A2');
    broker.store.revision = 3;

    expect(await source.save(PID, 'save')).toBe(false);
    expect(source.saveState.value).toBe('conflict');
    expect(source.draft.value).toBe('B');
    expect(source.savedContent.value).toBe('A');
    expect(broker.applyCalls).toBe(1);
    expect(broker.store.content).toBe('A2');

    // 回归（真实浏览器实锄）：edit 的 800ms debounce 迟到回写不得把 conflict 踩回 dirty。
    await new Promise((resolve) => setTimeout(resolve, 900));
    expect(source.saveState.value).toBe('conflict');

    // 冲突后重开同一文件 = 刷新基线：hash/revision 更新、草稿保留、不自动覆盖。
    await source.open(PID, PATH);
    expect(source.baseHash.value).toBe(hashOf('A2'));
    expect(source.revision.value).toBe(3);
    expect(source.savedContent.value).toBe('A2');
    expect(source.draft.value).toBe('B');
    expect(source.saveState.value).toBe('dirty');
    expect(broker.applyCalls).toBe(1);
  });

  test('TC-F2-10-04 契约字段：缺 hash/revision 显式报错；有则 baseHash 非 undefined', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes('broken-hash')) {
        return respond(200, { success: true, data: { path: PATH, content: 'x', baseHash: 'legacy' } });
      }
      if (url.includes('broken-revision')) {
        return respond(200, { success: true, data: { path: PATH, content: 'x', hash: 'h1' } });
      }
      return respond(200, { success: true, data: { path: PATH, content: 'A', hash: 'hash-A', revision: 1 } });
    }));
    await expect(readFile('broken-hash', PATH)).rejects.toThrow(/hash\/revision 契约字段/);
    await expect(readFile('broken-revision', PATH)).rejects.toThrow(/hash\/revision 契约字段/);

    const broker = installBroker({ content: 'A', hash: 'hash-A', revision: 1 });
    const source = useHypitSource();
    await source.refresh(PID);
    await source.open(PID, PATH);
    source.edit('B');
    await source.save(PID, 'save');
    const submitted = broker.createBodies[0]?.changes[0];
    expect(submitted?.baseHash).toBeDefined();
    expect(typeof submitted?.baseHash).toBe('string');
    expect(submitted?.baseHash).toBe('hash-A');
  });
});
