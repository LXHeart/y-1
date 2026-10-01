// @vitest-environment happy-dom
// fix2-c11.test.ts — C107F2-11（TC-F2-11-01～04）：工程深链、分页、创建导航和
// 跨项目请求世代隔离。组件层跑真实工作区/composable，HTTP 层用 fetch 替身；
// 世代语义用受控延迟释放逐段断言（A→B→A 只有当前世代生效）。
import { flushPromises, mount } from '@vue/test-utils';
import { createMemoryHistory, createRouter } from 'vue-router';
import { effectScope } from 'vue';
import { afterEach, describe, expect, test, vi } from 'vitest';
import VideoCloneWorkbench from '../VideoCloneWorkbench.vue';
import { useHypitPlan } from './useHypitPlan';
import { useHypitProjectScope } from './useHypitProjectScope';

const PID_A = '44444444-4444-4444-8444-444444444444';
const PID_B = '55555555-5555-4555-8555-555555555555';
const PID_DEEP = '66666666-6666-4666-8666-666666666666';

function project(id: string, title: string, status = 'ready', revision = 2): Record<string, unknown> {
  return {
    id, ownerAccountId: 'o1', title, mode: 'clone', status, revision, version: 1,
    selectedRun: 'main.svrun', sourceContext: null,
    createdAt: '2026-09-26T00:00:00Z', updatedAt: '2026-09-26T00:00:00Z',
  };
}

function respond(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });
}

type FetchRoute = {
  match: (url: string, method: string) => boolean;
  respond: (url: string) => Response | Promise<Response>;
};

/** 通用 fetch 替身：按测试提供的路由表应答，未命中 404；全程记 URL。 */
function installFetch(routes: FetchRoute[]): string[] {
  const calls: string[] = [];
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    const method = init?.method ?? 'GET';
    calls.push(`${method} ${url.replace('/api/hypit', '')}`);
    for (const route of routes) {
      if (route.match(url, method)) return route.respond(url);
    }
    return respond(404, { success: false, error: 'nf', code: 'hypit_not_found' });
  }));
  return calls;
}

/**
 * capabilities 每次都回新 Response 实例（Response body 只能消费一次，跨挂载
 * 复用同一实例会让第二次探测直接 TypeError）。
 */
function capabilitiesRoute(): FetchRoute {
  return { match: (url: string, method: string) => method === 'GET' && url.includes('/capabilities'), respond: () => respond(200, { success: true, data: { enabled: true, version: '0.2.16', features: [{ id: 'engine', installed: true, configured: true, prepared: true, ready: true, reason: null, action: null }], templates: [] } }) };
}

function commonRoutes(extra: FetchRoute[] = []): FetchRoute[] {
  return [
    capabilitiesRoute(),
    { match: (url, method) => method === 'GET' && /\/templates/.test(url), respond: () => respond(200, { success: true, data: { items: [] } }) },
    { match: (url, method) => method === 'GET' && /\/clone-plan/.test(url), respond: () => respond(404, { success: false, error: '尚无方案', code: 'hypit_not_found' }) },
    { match: (url, method) => method === 'GET' && /\/builds\?/.test(url), respond: () => respond(200, { success: true, data: { items: [] } }) },
    { match: (url, method) => method === 'GET' && /\/variants/.test(url), respond: () => respond(200, { success: true, data: { items: [] } }) },
    { match: (url, method) => method === 'GET' && /\/feedback/.test(url), respond: () => respond(200, { success: true, data: { file: 'FEEDBACK.json', comments: [], hash: 'x' } }) },
    ...extra,
  ];
}

function makeRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/video-clone/:projectId?', name: 'video-clone', component: VideoCloneWorkbench },
      { path: '/', redirect: '/video-clone' },
    ],
  });
}

async function mountWorkbenchAt(path: string) {
  const router = makeRouter();
  await router.push(path);
  await router.isReady();
  // GlModal 渲染进 Teleport：就地 stub 才能在 wrapper 内查到弹窗内容（happy-dom 已知坑）。
  const wrapper = mount(VideoCloneWorkbench, { global: { plugins: [router], stubs: { teleport: true } }, attachTo: document.body });
  return { wrapper, router };
}

/** TC-04 需要真实路由组件身份（onBeforeRouteLeave 只在 router-view 下生效）。 */
async function mountInRouter(path: string) {
  const router = makeRouter();
  await router.push(path);
  await router.isReady();
  const App = { template: '<router-view />' };
  const wrapper = mount(App, { global: { plugins: [router], stubs: { teleport: true } }, attachTo: document.body });
  return { wrapper, router };
}

afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
  document.body.innerHTML = '';
});

describe('C107F2-11 工程深链、分页、创建导航与世代隔离', () => {
  test('TC-F2-11-01 第51条工程深链：getProject 独立加载打开，不停在加载文案', async () => {
    const firstPage = Array.from({ length: 50 }, (_, index) => project(`77777777-7777-4777-8777-${String(100000000000 + index).padStart(12, '0')}`, `工程${index}`));
    installFetch(commonRoutes([
      { match: (url, method) => method === 'GET' && url.endsWith('/projects?limit=50'), respond: () => respond(200, { success: true, data: { items: firstPage, nextCursor: 'cur-2' } }) },
      { match: (url, method) => method === 'GET' && url.endsWith(`/projects/${PID_DEEP}`), respond: () => respond(200, { success: true, data: project(PID_DEEP, '第五十一条工程') }) },
    ]));

    const { wrapper } = await mountWorkbenchAt(`/video-clone/${PID_DEEP}`);
    await vi.waitFor(() => expect(wrapper.find('[data-testid="clone-project-header"]').exists()).toBe(true));
    expect(wrapper.find('[data-testid="clone-project-header"]').text()).toContain('第五十一条工程');
    expect(wrapper.find('[data-testid="clone-loading"]').exists()).toBe(false);

    // 404 深链：显示不存在与返回列表入口，不冒充加载中。
    installFetch(commonRoutes([
      { match: (url, method) => method === 'GET' && url.endsWith('/projects?limit=50'), respond: () => respond(200, { success: true, data: { items: firstPage, nextCursor: null } }) },
      { match: (url, method) => method === 'GET' && url.includes('/projects/88888888'), respond: () => respond(404, { success: false, error: '资源不存在', code: 'hypit_not_found' }) },
    ]));
    const missing = await mountWorkbenchAt('/video-clone/88888888-8888-4888-8888-888888888888');
    await vi.waitFor(() => expect(missing.wrapper.find('[data-testid="clone-project-notfound"]').exists()).toBe(true));
    expect(missing.wrapper.find('[data-testid="clone-notfound-back"]').exists()).toBe(true);
  });

  test('TC-F2-11-02 A→B→A 受控释放：只有当前世代生效，旧 finally 不结束当前 loading', async () => {
    // 世代表先单独钉死：switchProject 递增、旧世代 isCurrent=false、旧 signal abort。
    const gateScope = effectScope();
    const scope = gateScope.run(() => useHypitProjectScope())!;
    const gateA = scope.switchProject(PID_A);
    const gateB = scope.switchProject(PID_B);
    const gateA2 = scope.switchProject(PID_A);
    expect(gateA.generation).toBe(1);
    expect(gateB.generation).toBe(2);
    expect(gateA2.generation).toBe(3);
    expect(gateA.isCurrent()).toBe(false);
    expect(gateB.isCurrent()).toBe(false);
    expect(gateA2.isCurrent()).toBe(true);
    expect(gateA.signal.aborted).toBe(true);
    expect(gateB.signal.aborted).toBe(true);
    expect(gateA2.signal.aborted).toBe(false);

    // 域 composable 层：三段延迟受控释放（按调用次序独立 deferred，同 id 不复用）。
    type Deferred = { promise: Promise<Response>; resolve: (value: Response) => void };
    const deferreds: Deferred[] = [];
    const makeDeferred = (): Deferred => {
      let resolve!: (value: Response) => void;
      const promise = new Promise<Response>((done) => { resolve = done; });
      const deferred = { promise, resolve };
      deferreds.push(deferred);
      return deferred;
    };
    const order: string[] = [];
    installFetch([{
      match: (url) => url.includes('/clone-plan'),
      respond: (url) => {
        const id = url.split('/projects/')[1]?.split('/clone-plan')[0] ?? '?';
        order.push(`start:${id}`);
        return makeDeferred().promise;
      },
    }]);

    const planScope = effectScope();
    const plan = planScope.run(() => useHypitPlan())!;
    const first = plan.refresh(PID_A);
    const second = plan.refresh(PID_B);
    const third = plan.refresh(PID_A);
    await vi.waitFor(() => expect(order).toEqual([`start:${PID_A}`, `start:${PID_B}`, `start:${PID_A}`]));
    expect(plan.loading.value).toBe(true);

    // B（旧世代）先返回：不得写入，也不得结束 loading。
    deferreds[1]!.resolve(respond(200, { success: true, data: { pricing: { items: [], totalCredits: 0 }, materials: [], plan: null } }));
    await Promise.resolve();
    await flushPromises();
    expect(plan.plan.value).toBeNull();
    expect(plan.loading.value).toBe(true);

    // A 第一段（更旧）再返回：同样整体丢弃。
    deferreds[0]!.resolve(respond(200, { success: true, data: { stale: true } }));
    await flushPromises();
    expect(plan.plan.value).toBeNull();
    expect(plan.loading.value).toBe(true);

    // A 第二段（当前世代）返回：写入并结束 loading。
    deferreds[2]!.resolve(respond(200, { success: true, data: { pricing: { items: [], totalCredits: 0 }, materials: [], plan: null } }));
    await first; await second; await third;
    expect(plan.plan.value).not.toBeNull();
    expect(plan.plan.value).not.toHaveProperty('stale');
    expect(plan.loading.value).toBe(false);
    gateScope.stop();
    planScope.stop();
  });

  test('TC-F2-11-03 创建返回 provisioning：导航到新深链、轮询至 ready、刷新可继续', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const created = project(PID_B, '新克隆工程', 'provisioning', 0);
    let currentStatus = 'provisioning';
    const projectGetCalls: string[] = [];
    installFetch(commonRoutes([
      { match: (url, method) => method === 'GET' && url.endsWith('/projects?limit=50'), respond: () => respond(200, { success: true, data: { items: [{ ...created, status: currentStatus }], nextCursor: null } }) },
      { match: (url, method) => method === 'GET' && url.endsWith(`/projects/${PID_B}`), respond: () => { projectGetCalls.push(`${Date.now()}:${currentStatus}`); return respond(200, { success: true, data: { ...created, status: currentStatus } }); } },
      { match: (url, method) => method === 'POST' && url.endsWith('/projects'), respond: () => respond(202, { success: true, data: { project: created, job: { jobId: 'job-1', state: 'queued', resourceId: PID_B } } }) },
    ]));

    const { wrapper, router } = await mountWorkbenchAt('/video-clone');
    await vi.waitFor(() => expect(wrapper.find('[data-testid="clone-new-project"]').exists()).toBe(true));
    await wrapper.get('[data-testid="clone-new-project"]').trigger('click');
    await wrapper.get('[data-testid="clone-new-title"]').setValue('新克隆工程');
    await wrapper.get('[data-testid="clone-new-submit"]').trigger('submit');
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe(`/video-clone/${PID_B}`));
    await vi.waitFor(() => expect(wrapper.find('[data-testid="clone-project-header"]').text()).toContain('新克隆工程'));

    // provisioning → 2 秒轮询；两次后 ready，轮询即停。
    currentStatus = 'ready';
    await vi.advanceTimersByTimeAsync(2000);
    await vi.advanceTimersByTimeAsync(2000);
    const callsAfterReady = projectGetCalls.length;
    expect(callsAfterReady).toBeGreaterThanOrEqual(1);
    await vi.advanceTimersByTimeAsync(8000);
    expect(projectGetCalls.length).toBe(callsAfterReady);

    // 刷新可继续：同 URL 重挂载后工程仍能打开（不依赖创建会话）。
    const reloaded = await mountWorkbenchAt(`/video-clone/${PID_B}`);
    await vi.waitFor(() => expect(reloaded.wrapper.find('[data-testid="clone-project-header"]').text()).toContain('新克隆工程'));
  });

  test('TC-F2-11-04 未保存草稿切换：取消留 A 草稿；确认后 B 不带 A 草稿/会话', async () => {
    installFetch(commonRoutes([
      { match: (url, method) => method === 'GET' && url.endsWith('/projects?limit=50'), respond: () => respond(200, { success: true, data: { items: [project(PID_A, '工程A'), project(PID_B, '工程B')], nextCursor: null } }) },
      { match: (url, method) => method === 'GET' && url.includes('/files'), respond: () => respond(200, { success: true, data: { revision: 2, manifestHash: 'h', files: [{ path: 'main.svml', sizeBytes: 3, sha256: 'a' }] } }) },
      { match: (url, method) => method === 'GET' && url.includes('/file?path='), respond: () => respond(200, { success: true, data: { path: 'main.svml', content: 'A 原文', hash: 'hash-A', revision: 2 } }) },
    ]));

    const { wrapper, router } = await mountInRouter(`/video-clone/${PID_A}?step=generate`);
    await vi.waitFor(() => expect(wrapper.find('[data-testid="clone-project-header"]').text()).toContain('工程A'));
    await wrapper.get('.clone-source-file').trigger('click');
    await vi.waitFor(() => expect((wrapper.get('[data-testid="clone-source-textarea"]').element as HTMLTextAreaElement).value).toBe('A 原文'));
    await wrapper.get('[data-testid="clone-source-textarea"]').setValue('A 未保存草稿');

    // 切 B：先取消——URL 不动、草稿保留。
    void router.push(`/video-clone/${PID_B}`);
    await vi.waitFor(() => expect(wrapper.find('[data-testid="clone-leave-cancel"]').exists()).toBe(true));
    await wrapper.get('[data-testid="clone-leave-cancel"]').trigger('click');
    await flushPromises();
    expect(router.currentRoute.value.path).toBe(`/video-clone/${PID_A}`);
    expect((wrapper.get('[data-testid="clone-source-textarea"]').element as HTMLTextAreaElement).value).toBe('A 未保存草稿');

    // 再切 B：确认离开——URL 到 B、A 草稿与会话清空（回到“选择文件”空态）。
    void router.push(`/video-clone/${PID_B}`);
    await vi.waitFor(() => expect(wrapper.find('[data-testid="clone-leave-confirm"]').exists()).toBe(true));
    await wrapper.get('[data-testid="clone-leave-confirm"]').trigger('click');
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe(`/video-clone/${PID_B}`));
    await vi.waitFor(() => expect(wrapper.find('[data-testid="clone-project-header"]').text()).toContain('工程B'));
    expect(wrapper.find('[data-testid="clone-source-textarea"]').exists()).toBe(false);
    expect(wrapper.text()).toContain('选择左侧文件开始编辑');
  });
});
