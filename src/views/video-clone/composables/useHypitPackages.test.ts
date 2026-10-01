// @vitest-environment happy-dom
// useHypitPackages.test.ts — C107F2-30 composable 全环（补充 V-15 变更行覆盖）：
// 导出 202→轮询 download→真实浏览器下载事件；导入 XHR 进度→202→provisioning→ready
// 导航；失败/超时/断点重查（同 requestId 幂等）/令牌失效/卸载中止。fetch 与 XHR 均
// 打桩（无网络），真实 composable + 真实 hypit-api 客户端在环。
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { defineComponent, effectScope, type EffectScope } from 'vue';
import { mount } from '@vue/test-utils';
import { useHypitPackages } from './useHypitPackages';

const PROJECT = '55555555-5555-4555-8555-555555555555';
const EXPORT_ID = 'ex-0001';
const NEW_PROJECT = '66666666-6666-4666-8666-666666666666';

function respond(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });
}

type FetchCall = { method: string; url: string; body: unknown };

/** fetch 桩：按 (method,url) 前缀出队响应脚本；记录调用序。 */
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

/** XHR 桩：send() 即挂起，用例手动 complete()/fail()；捕获 FormData 便于断言。 */
class FakeXhr {
  static sent: FakeXhr[] = [];
  status = 0;
  responseText = '';
  withCredentials = false;
  upload: { onprogress: ((e: { lengthComputable: boolean; loaded: number; total: number }) => void) | null } = { onprogress: null };
  onload: (() => void) | null = null;
  onerror: (() => void) | null = null;
  onabort: (() => void) | null = null;
  form: FormData | null = null;
  method = '';
  url = '';
  open(method: string, url: string): void { this.method = method; this.url = url; }
  send(form: FormData): void { this.form = form; FakeXhr.sent.push(this); }
  abort(): void { this.onabort?.(); }
  complete(status: number, body: unknown, progress = 100): void {
    this.upload.onprogress?.({ lengthComputable: true, loaded: progress, total: 100 });
    this.status = status;
    this.responseText = JSON.stringify(body);
    this.onload?.();
  }
  networkError(): void { this.onerror?.(); }
}

beforeEach(() => {
  FakeXhr.sent = [];
  vi.stubGlobal('XMLHttpRequest', FakeXhr as unknown as typeof XMLHttpRequest);
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

const DOWNLOAD = { downloadPath: '/api/hypit/exports/ex-0001/package', filename: 'proj.zip' };

function exportRoutes(statusQueue: Array<Record<string, unknown>>): { calls: FetchCall[] } {
  return installFetch([
    {
      match: (c) => c.method === 'POST' && c.url.endsWith(`/api/hypit/projects/${PROJECT}/export`),
      respond: () => respond({ success: true, data: { exportId: EXPORT_ID, jobId: 'j1', status: 'accepted' } }, 202),
    },
    {
      match: (c) => c.method === 'GET' && c.url.includes(`/api/hypit/exports/${EXPORT_ID}`),
      respond: () => respond({ success: true, data: statusQueue.length > 1 ? statusQueue.shift() : statusQueue[0] }),
    },
  ]);
}

function importRoutes(projectStatuses: string[]): { calls: FetchCall[] } {
  return installFetch([
    {
      match: (c) => c.method === 'GET' && c.url.includes(`/api/hypit/projects/`),
      respond: () => {
        const status = projectStatuses.length > 1 ? projectStatuses.shift() : projectStatuses[0];
        return respond({ success: true, data: { id: NEW_PROJECT, status, revision: 1 } });
      },
    },
  ]);
}

describe('C107F2-30 useHypitPackages 导出环', () => {
  test('TC-P-01 202→轮询 download 就绪→ready；triggerBrowserDownload 触发真实 anchor 下载', async () => {
    const { calls } = exportRoutes([{ status: 'building' }, { status: 'ready', download: DOWNLOAD }]);
    const scope: EffectScope = effectScope();
    const packages = scope.run(() => useHypitPackages())!;
    expect(packages.exportPhase.value).toBe('idle');

    await packages.startExport(PROJECT, '标题一');
    expect(packages.exportPhase.value).toBe('preparing');
    // 轮询间隔 1200ms > waitFor 默认 1s 超时，显式放宽。
    await vi.waitFor(() => expect(packages.exportPhase.value).toBe('ready'), { timeout: 4000 });
    expect(packages.download.value?.filename).toBe('proj.zip');
    // POST 载荷带 requestId + title（幂等键入环）。
    const post = calls.find((c) => c.method === 'POST');
    expect((post!.body as Record<string, unknown>).title).toBe('标题一');
    expect((post!.body as Record<string, unknown>).requestId).toBeTruthy();

    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});
    packages.triggerBrowserDownload();
    expect(click).toHaveBeenCalledTimes(1);
    click.mockRestore();
    scope.stop();
  });

  test('TC-P-02 轮询见 failed → 如实 failed + 文案，不伪造 ready', async () => {
    exportRoutes([{ status: 'failed', error: { code: 'hypit_export_failed' } }]);
    const scope = effectScope();
    const packages = scope.run(() => useHypitPackages())!;
    await packages.startExport(PROJECT);
    await vi.waitFor(() => expect(packages.exportPhase.value).toBe('failed'));
    expect(packages.exportError.value).toContain('导出失败');
    expect(packages.download.value).toBeNull();
    scope.stop();
  });

  test('TC-P-03 100 轮未就绪 → 超时文案', async () => {
    vi.useFakeTimers();
    exportRoutes([{ status: 'building' }]);
    const scope = effectScope();
    const packages = scope.run(() => useHypitPackages())!;
    const done = packages.startExport(PROJECT);
    await vi.advanceTimersByTimeAsync(1200); // 首轮 poll 立即跑，此后逐轮
    for (let i = 0; i < 120; i += 1) await vi.advanceTimersByTimeAsync(1200);
    await done;
    expect(packages.exportPhase.value).toBe('failed');
    expect(packages.exportError.value).toContain('导出超时');
    scope.stop();
  });

  test('TC-P-04 令牌失效：第二次 startExport 后，第一次的迟到轮询不覆盖新状态', async () => {
    let mode: 'first' | 'second' = 'first';
    const { calls } = installFetch([
      {
        match: (c) => c.method === 'POST' && c.url.endsWith('/export'),
        respond: () => respond({ success: true, data: { exportId: EXPORT_ID, jobId: 'j', status: 'accepted' } }, 202),
      },
      {
        match: (c) => c.method === 'GET' && c.url.includes('/api/hypit/exports/'),
        respond: () => respond({ success: true, data: mode === 'first'
          ? { status: 'failed' }
          : { status: 'ready', download: DOWNLOAD } }),
      },
    ]);
    vi.useFakeTimers();
    const scope = effectScope();
    const packages = scope.run(() => useHypitPackages())!;
    const first = packages.startExport(PROJECT);
    await vi.advanceTimersByTimeAsync(1200); // 第一轮 poll 已发（failed）
    mode = 'second';
    await first;
    await packages.startExport(PROJECT);
    await vi.advanceTimersByTimeAsync(1200);
    expect(packages.exportPhase.value).toBe('ready');
    expect(calls.filter((c) => c.method === 'POST')).toHaveLength(2);
    scope.stop();
  });

  test('TC-P-04b 提交即失败与轮询网络错误 → failed 透因', async () => {
    installFetch([
      { match: (c) => c.method === 'POST' && c.url.endsWith('/export'),
        respond: () => respond({ success: false, error: { code: 'hypit_operator_required', message: '仅 operator' } }, 403) },
    ]);
    const scope = effectScope();
    const packages = scope.run(() => useHypitPackages())!;
    await packages.startExport(PROJECT);
    expect(packages.exportPhase.value).toBe('failed');
    expect(packages.exportError.value).toBe('仅 operator');

    // 轮询中 exportStatus 网络错误 → failed + 错误消息（非超时文案）。
    installFetch([
      { match: (c) => c.method === 'POST' && c.url.endsWith('/export'),
        respond: () => respond({ success: true, data: { exportId: EXPORT_ID, jobId: 'j', status: 'accepted' } }, 202) },
      { match: () => true, respond: () => new Response('boom', { status: 502 }) },
    ]);
    await packages.startExport(PROJECT);
    await vi.waitFor(() => expect(packages.exportPhase.value).toBe('failed'));
    expect(packages.exportError.value).toContain('请求失败（502）');
    scope.stop();
  });

  test('TC-P-05 reset 清空全域（含下载元数据与导入域）', async () => {
    exportRoutes([{ status: 'ready', download: DOWNLOAD }]);
    const scope = effectScope();
    const packages = scope.run(() => useHypitPackages())!;
    await packages.startExport(PROJECT);
    await vi.waitFor(() => expect(packages.exportPhase.value).toBe('ready'));
    packages.reset();
    expect(packages.exportPhase.value).toBe('idle');
    expect(packages.download.value).toBeNull();
    expect(packages.importPhase.value).toBe('idle');
    scope.stop();
  });
});

describe('C107F2-30 useHypitPackages 导入环', () => {
  function pickXhr(): FakeXhr {
    expect(FakeXhr.sent.length).toBeGreaterThan(0);
    return FakeXhr.sent[FakeXhr.sent.length - 1]!;
  }

  test('TC-P-11 选包→XHR 进度→202→provisioning→ready→onImported 新工程', async () => {
    importRoutes(['provisioning', 'ready']);
    const scope = effectScope();
    const packages = scope.run(() => useHypitPackages())!;
    expect(packages.importPhase.value).toBe('idle');
    packages.selectFile(new File(['PK'], 'proj.zip'));
    expect(packages.importPhase.value).toBe('selected');

    const imported: string[] = [];
    const done = packages.startImport((id) => imported.push(id));
    await vi.waitFor(() => expect(FakeXhr.sent.length).toBe(1));
    pickXhr().complete(202, { success: true, data: { jobId: 'j9', projectId: NEW_PROJECT, status: 'accepted' } }, 60);
    // onload→resolve→续跑跨微任务，等待状态迁移后再断言。
    await vi.waitFor(() => expect(packages.importPhase.value).toBe('provisioning'));
    expect(packages.importProgress.value).toBe(60);
    // 多轮 poll 的后续轮在 detached 定时器上——等终态而非 await done（只含首轮）。
    await vi.waitFor(() => expect(packages.importPhase.value).toBe('ready'), { timeout: 4000 });
    await done;
    expect(packages.importedProjectId.value).toBe(NEW_PROJECT);
    expect(imported).toEqual([NEW_PROJECT]);
    // multipart 带 requestId + file 名。
    expect(pickXhr().form?.get('requestId')).toBeTruthy();
    expect((pickXhr().form?.get('file') as File).name).toBe('proj.zip');
    scope.stop();
  });

  test('TC-P-12 provisioning_failed → failed + 包校验文案', async () => {
    importRoutes(['provisioning_failed']);
    const scope = effectScope();
    const packages = scope.run(() => useHypitPackages())!;
    packages.selectFile(new File(['PK'], 'proj.zip'));
    const done = packages.startImport(() => {});
    await vi.waitFor(() => FakeXhr.sent.length === 1);
    pickXhr().complete(202, { success: true, data: { jobId: 'j', projectId: NEW_PROJECT, status: 'accepted' } });
    await done;
    expect(packages.importPhase.value).toBe('failed');
    expect(packages.importError.value).toContain('包未通过校验');
    scope.stop();
  });

  test('TC-P-13 XHR 网络错误 → failed + 中断文案；重试复用同一 requestId（断点重查幂等）', async () => {
    importRoutes(['ready']);
    const scope = effectScope();
    const packages = scope.run(() => useHypitPackages())!;
    packages.selectFile(new File(['PK'], 'proj.zip'));
    const done = packages.startImport(() => {});
    await vi.waitFor(() => FakeXhr.sent.length === 1);
    const firstRequestId = pickXhr().form?.get('requestId');
    pickXhr().networkError();
    await done;
    expect(packages.importPhase.value).toBe('failed');
    expect(packages.importError.value).toContain('上传中断');

    // 重试：同 requestId（不产生第二工程）；这次成功。
    const retried = packages.startImport(() => {});
    await vi.waitFor(() => FakeXhr.sent.length === 2);
    expect(pickXhr().form?.get('requestId')).toBe(firstRequestId);
    pickXhr().complete(202, { success: true, data: { jobId: 'j', projectId: NEW_PROJECT, status: 'accepted' } });
    await retried;
    expect(packages.importPhase.value).toBe('ready');
    scope.stop();
  });

  test('TC-P-13b 状态查询瞬时失败可重试（同轮续跑不判死）；持续失败到 100 轮判超时', async () => {
    vi.useFakeTimers();
    let failures = 0;
    installFetch([
      { match: (c) => c.method === 'GET' && c.url.includes('/api/hypit/projects/'),
        respond: () => {
          if (failures < 2) { failures += 1; return new Response('db conn', { status: 503 }); }
          return respond({ success: true, data: { id: NEW_PROJECT, status: 'ready', revision: 1 } });
        } },
    ]);
    const scope = effectScope();
    const packages = scope.run(() => useHypitPackages())!;
    packages.selectFile(new File(['PK'], 'proj.zip'));
    const imported: string[] = [];
    void packages.startImport((id) => imported.push(id));
    await vi.advanceTimersByTimeAsync(0); // 微任务清空：XHR 已挂起
    FakeXhr.sent[FakeXhr.sent.length - 1]!.complete(202, { success: true, data: { jobId: 'j', projectId: NEW_PROJECT, status: 'accepted' } });
    // 首轮 poll 立即执行（503→不判死）；此后每 1200ms 一轮。
    await vi.advanceTimersByTimeAsync(1200); // 次轮 503
    expect(packages.importPhase.value).toBe('provisioning');
    await vi.advanceTimersByTimeAsync(1200); // 三轮成功 → ready
    expect(packages.importPhase.value).toBe('ready');
    expect(imported).toEqual([NEW_PROJECT]);
    scope.stop();

    // 持续失败 100 轮 → 超时文案（同 requestId 幂等重试提示）。
    installFetch([
      { match: () => true, respond: () => new Response('db down', { status: 503 }) },
    ]);
    const scope2 = effectScope();
    const packages2 = scope2.run(() => useHypitPackages())!;
    packages2.selectFile(new File(['PK'], 'proj.zip'));
    const done2 = packages2.startImport(() => {});
    await vi.advanceTimersByTimeAsync(0);
    FakeXhr.sent[FakeXhr.sent.length - 1]!.complete(202, { success: true, data: { jobId: 'j', projectId: NEW_PROJECT, status: 'accepted' } });
    for (let i = 0; i < 130; i += 1) await vi.advanceTimersByTimeAsync(1200);
    await done2;
    expect(packages2.importPhase.value).toBe('failed');
    // 查询类失败到 100 轮：catch 分支以最后错误消息判死（外层「查询超时」文案
    // 属「有响应但永不终态」路径）。
    expect(packages2.importError.value).toBe('请求失败（503）');
    scope2.stop();
  });

  test('TC-P-14 未选包 startImport 直接返回（零请求零状态迁移）', async () => {
    const { calls } = importRoutes(['ready']);
    const scope = effectScope();
    const packages = scope.run(() => useHypitPackages())!;
    await packages.startImport(() => {});
    expect(packages.importPhase.value).toBe('idle');
    expect(calls).toHaveLength(0);
    scope.stop();
  });

  test('TC-P-15 卸载中止：unmount 触发 controller.abort，在途轮询静默（不迁移不抛错）', async () => {
    vi.useFakeTimers();
    exportRoutes([{ status: 'building' }]);
    // onUnmounted 需要真实组件实例（effectScope 不注册生命周期）——挂最小宿主。
    const host = defineComponent({
      setup(_, { expose }) {
        const packages = useHypitPackages();
        expose({ packages });
        return () => null;
      },
    });
    const wrapper = mount(host);
    const packages = (wrapper.vm as unknown as { packages: ReturnType<typeof useHypitPackages> }).packages;
    const done = packages.startExport(PROJECT);
    await vi.advanceTimersByTimeAsync(1200);
    expect(packages.exportPhase.value).toBe('preparing');
    wrapper.unmount(); // onUnmounted → controller.abort + clearPoll
    await vi.advanceTimersByTimeAsync(1200);
    await done;
    expect(packages.exportPhase.value).toBe('preparing'); // 中止后不迁移
  });
});
