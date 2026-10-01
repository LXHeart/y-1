// @vitest-environment happy-dom
// useHypitAssetsProjects.test.ts — C107F2-31 / C107F2-11 composable 分支补环
// （补充 V-15 变更行覆盖）：素材取数/交接/上传进度/取消/重试/切工程重载 +
// 工程列表 cursor 分页去重/创建/删除/upsert。fetch/XHR 打桩，真实 composable
// 与真实 hypit-api 客户端在环（最小宿主组件提供 onMounted/watch 生命周期）。
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { defineComponent, ref } from 'vue';
import { mount } from '@vue/test-utils';
import { useHypitAssets } from './useHypitAssets';
import { useHypitProjects } from './useHypitProjects';
import type { HypitProject } from '../../../types/hypit';

function respond(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });
}

type FetchCall = { method: string; url: string; body: unknown };

function installFetch(routes: Array<{ match: (c: FetchCall) => boolean; respond: (c: FetchCall) => Response }>): { calls: FetchCall[] } {
  const calls: FetchCall[] = [];
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const call: FetchCall = {
      method: (init?.method ?? 'GET').toUpperCase(),
      url: String(input),
      body: typeof init?.body === 'string' ? JSON.parse(init.body) : init?.body,
    };
    calls.push(call);
    const route = routes.find((r) => r.match(call));
    if (!route) throw new Error(`fetch 桩未覆盖：${call.method} ${call.url}`);
    return route.respond(call);
  }));
  return { calls };
}

class FakeXhr {
  static sent: FakeXhr[] = [];
  status = 0;
  responseText = '';
  withCredentials = false;
  upload: { onprogress: ((e: { lengthComputable: boolean; loaded: number; total: number }) => void) | null } = { onprogress: null };
  onload: (() => void) | null = null;
  onerror: (() => void) | null = null;
  onabort: (() => void) | null = null;
  open(): void {}
  send(): void { FakeXhr.sent.push(this); }
  abort(): void { this.onabort?.(); }
  complete(status: number, body: unknown, progress = 100): void {
    this.upload.onprogress?.({ lengthComputable: true, loaded: progress, total: 100 });
    this.status = status;
    this.responseText = JSON.stringify(body);
    this.onload?.();
  }
}

beforeEach(() => {
  FakeXhr.sent = [];
  vi.stubGlobal('XMLHttpRequest', FakeXhr as unknown as typeof XMLHttpRequest);
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

const PID = '77777777-7777-4777-8777-777777777777';
function project(id: string = PID, status = 'ready'): HypitProject {
  return { id, title: 't', mode: 'clone', status, revision: 1 } as unknown as HypitProject;
}
const ASSET = { id: 'a1', projectId: PID, role: 'reference', originKind: 'upload', originUrl: null,
  mediaId: 'm1', resourceHandle: 'r1', mimeType: 'video/mp4', sizeBytes: 10, sha256: 'x'.repeat(64), status: 'ready' };

describe('C107F2-31 useHypitAssets', () => {
  function assetsRoutes(items: unknown[] = [ASSET], importNote?: string): { calls: FetchCall[] } {
    return installFetch([
      { match: (c) => c.method === 'GET' && c.url.endsWith(`/api/hypit/projects/${PID}/assets`),
        respond: () => respond({ success: true, data: { items } }) },
      { match: (c) => c.method === 'POST' && c.url.endsWith('/assets/import-source'),
        respond: () => respond({ success: true, data: { sourceKind: 'analysis', asset: null, ...(importNote === undefined ? {} : { note: importNote }) } }) },
    ]);
  }

  function mountAssets(proj = ref(project())) {
    const host = defineComponent({
      setup(_, { expose }) {
        const packages = useHypitAssets({ project: () => proj.value });
        expose({ packages });
        return () => null;
      },
    });
    const wrapper = mount(host);
    return { wrapper, api: (wrapper.vm as unknown as { packages: ReturnType<typeof useHypitAssets> }).packages, proj };
  }

  test('TC-A-01 挂载即拉列表；handoffSource 物化后刷新并透出 note', async () => {
    const { calls } = assetsRoutes([ASSET], '已交接分析');
    const { wrapper, api } = mountAssets();
    await vi.waitFor(() => expect(api.assets.value).toHaveLength(1));
    expect(api.loading.value).toBe(false);

    await api.handoffSource();
    expect(api.handoffBusy.value).toBe(false);
    expect(api.handoffNote.value).toBe('已交接分析');
    expect(calls.filter((c) => c.url.endsWith('/assets/import-source'))).toHaveLength(1);
    // 交接后刷新（两次列表 GET：挂载一次 + 交接后一次）。
    expect(calls.filter((c) => c.url.endsWith('/assets'))).toHaveLength(2);
    wrapper.unmount();
  });

  test('TC-A-02 handoffSource 失败 → error 文案且 note 清空；列表失败 → error', async () => {
    const { calls } = installFetch([
      { match: (c) => c.url.endsWith('/assets/import-source'),
        respond: () => respond({ success: false, error: { code: 'hypit_source_missing', message: '缺 sourceContext' } }, 409) },
      { match: (c) => c.url.endsWith('/assets'), respond: () => respond({ success: false, error: '引擎离线' }, 503) },
    ]);
    const { wrapper, api } = mountAssets();
    await vi.waitFor(() => expect(api.error.value).toBe('引擎离线'));
    await api.handoffSource();
    expect(api.handoffNote.value).toBeNull();
    expect(api.error.value).toContain('缺 sourceContext');
    expect(calls.filter((c) => c.method === 'POST')).toHaveLength(1);
    wrapper.unmount();
  });

  test('TC-A-03 上传：XHR 进度→202→清 pending→刷新；失败保留已选文件可重试', async () => {
    assetsRoutes([ASSET]);
    const { wrapper, api } = mountAssets();
    await vi.waitFor(() => expect(api.assets.value).toHaveLength(1));

    const file = new File(['AA'], 'ref.mp4');
    const uploadDone = api.upload(file);
    await vi.waitFor(() => expect(FakeXhr.sent.length).toBe(1));
    FakeXhr.sent[0]!.complete(202, { success: true, data: ASSET }, 70);
    expect(api.uploadProgress.value).toBe(70);
    const asset = await uploadDone;
    expect(asset?.id).toBe('a1');
    expect(api.pendingFile.value).toBeNull();
    expect(api.uploading.value).toBe(false);

    // 失败路径：错误文案 + 保留文件 + retryPending 重传同一文件。
    const failed = api.upload(file);
    await vi.waitFor(() => expect(FakeXhr.sent.length).toBe(2));
    FakeXhr.sent[1]!.complete(413, { success: false, error: { code: 'hypit_too_large', message: '包太大' } });
    expect(await failed).toBeNull();
    expect(api.uploadError.value).toBe('包太大');
    // ref 深代理：pendingFile.value 是 file 的 reactive 代理，非同一引用——深比较。
    expect(api.pendingFile.value).toStrictEqual(file);
    const retried = api.retryPending();
    await vi.waitFor(() => expect(FakeXhr.sent.length).toBe(3));
    FakeXhr.sent[2]!.complete(202, { success: true, data: ASSET });
    expect((await retried)?.id).toBe('a1');
    wrapper.unmount();
  });

  test('TC-A-04 cancelUpload 中止在途上传（uploading 复位、进度清零）', async () => {
    assetsRoutes([]);
    const { wrapper, api } = mountAssets();
    await vi.waitFor(() => expect(api.loading.value).toBe(false));
    void api.upload(new File(['A'], 'x.mp4'));
    await vi.waitFor(() => expect(FakeXhr.sent.length).toBe(1));
    expect(api.uploading.value).toBe(true);
    api.cancelUpload();
    expect(api.uploading.value).toBe(false);
    expect(api.uploadProgress.value).toBe(0);
    wrapper.unmount();
  });

  test('TC-A-05 retryPending 无 pending → null；切工程 watch 清域并重拉', async () => {
    const { calls } = assetsRoutes([]);
    const proj = ref(project());
    const { wrapper, api } = mountAssets(proj);
    await vi.waitFor(() => expect(api.loading.value).toBe(false));
    expect(await api.retryPending()).toBeNull();

    proj.value = project('88888888-8888-4888-8888-888888888888');
    await vi.waitFor(() => expect(calls.filter((c) => c.method === 'GET').length).toBeGreaterThanOrEqual(2));
    wrapper.unmount();
  });
});

describe('C107F2-11 useHypitProjects cursor 分页', () => {
  const A = project('aaaaaaaa-1111-4111-8111-00000000000a');
  const B = project('bbbbbbbb-2222-4222-8222-00000000000b');
  const A2 = { ...A, title: 't2' };

  function projectsRoutes(pages: Array<{ items: HypitProject[]; nextCursor: string | null }>): { calls: FetchCall[] } {
    return installFetch([
      { match: (c) => c.method === 'GET' && c.url === '/api/hypit/projects',
        respond: () => respond({ success: true, data: pages.length > 1 ? pages.shift()! : pages[0]! }) },
      { match: (c) => c.method === 'GET' && c.url.includes('/api/hypit/projects?'),
        respond: () => respond({ success: true, data: pages.length > 1 ? pages.shift()! : pages[0]! }) },
      { match: (c) => c.method === 'POST' && c.url === '/api/hypit/projects',
        respond: () => respond({ success: true, data: { project: B, provisioning: true } }, 202) },
      { match: (c) => c.method === 'DELETE',
        respond: () => respond({ success: true, data: { jobId: 'j' } }) },
    ]);
  }

  test('TC-PJ-01 首页→loadMore 按 id 去重且新页字段为准；cursor 到底后 no-op', async () => {
    const { calls } = projectsRoutes([
      { items: [A], nextCursor: 'c2' },
      { items: [{ ...A2 }, B], nextCursor: null },
    ]);
    const api = useHypitProjects();
    await vi.waitFor(() => expect(api.projects.value).toHaveLength(1));
    expect(api.nextCursor.value).toBe('c2');

    await api.loadMore();
    expect(api.projects.value.map((p) => p.id)).toEqual([A.id, B.id]);
    expect(api.projects.value.find((p) => p.id === A.id)?.title).toBe('t2'); // 新页字段为准
    expect(api.nextCursor.value).toBeNull();
    await api.loadMore(); // 到底 → 零请求 no-op
    expect(calls.filter((c) => c.method === 'GET')).toHaveLength(2);
  });

  test('TC-PJ-02 create 202→刷新返回工程；失败透出 status+message', async () => {
    projectsRoutes([{ items: [B], nextCursor: null }]);
    const api = useHypitProjects();
    await vi.waitFor(() => expect(api.projects.value).toHaveLength(1));
    const created = await api.create({ title: '新工程', mode: 'clone', sourceContext: { kind: 'ai_run', id: 'r1' } });
    expect(created?.id).toBe(B.id);
    expect(api.submitting.value).toBe(false);
    const body = (await Promise.resolve()) ?? null;

    const { calls } = installFetch([
      { match: (c) => c.method === 'POST' && c.url === '/api/hypit/projects',
        respond: () => respond({ success: false, error: { code: 'hypit_invalid_title', message: '标题过长' } }, 400) },
    ]);
    const failed = await api.create({ title: 'x'.repeat(500), mode: 'clone' });
    expect(failed).toBeNull();
    expect(api.error.value?.status).toBe(400);
    expect(api.error.value?.message).toBe('标题过长');
    expect(calls.filter((c) => c.method === 'POST')).toHaveLength(1);
    void body;
  });

  test('TC-PJ-03 remove 成功刷新；失败返回 false 并透出错误', async () => {
    projectsRoutes([{ items: [], nextCursor: null }]);
    const api = useHypitProjects();
    expect(await api.remove(B.id)).toBe(true);
    installFetch([
      { match: () => true, respond: () => respond({ success: false, error: '404' }, 404) },
    ]);
    expect(await api.remove(B.id)).toBe(false);
    expect(api.error.value?.status).toBe(404);
  });

  test('TC-PJ-04 upsert：在列表原位替换；不在列表头部插入；readyProjects 过滤', async () => {
    projectsRoutes([{ items: [A], nextCursor: null }]);
    const api = useHypitProjects();
    await vi.waitFor(() => expect(api.projects.value).toHaveLength(1));
    api.upsert({ ...A, status: 'provisioning' });
    expect(api.projects.value[0]!.status).toBe('provisioning');
    expect(api.readyProjects.value).toHaveLength(0);
    api.upsert(B); // B 造数即 ready → 头插且计入 ready
    expect(api.projects.value[0]!.id).toBe(B.id);
    expect(api.readyProjects.value.map((p) => p.id)).toEqual([B.id]);
    api.upsert({ ...A, status: 'ready' });
    expect(api.readyProjects.value.map((p) => p.id)).toEqual([B.id, A.id]);
  });
});
