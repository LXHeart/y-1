// @vitest-environment happy-dom
// fix2-c09.test.ts — C107F2-09（TC-F2-09-01～04）：真实 readiness 与工作区顶层
// 登录、禁用、不可用状态。组件层：mock HTTP（fetch 替身），运行真实 composable
// 与工作区组件，不 mock 被测动作函数。请求计数直接数 fetch 调用。
import { mount, flushPromises } from '@vue/test-utils';
import { createMemoryHistory, createRouter } from 'vue-router';
import { effectScope } from 'vue';
import { afterEach, describe, expect, test, vi } from 'vitest';
import { useHypitRuntime } from './useHypitRuntime';

const readyProject = {
  id: '44444444-4444-4444-8444-444444444444',
  ownerAccountId: 'o1',
  title: '榜单复刻',
  mode: 'clone',
  status: 'ready',
  revision: 3,
  version: 1,
  selectedRun: 'main.svrun',
  sourceContext: null,
  createdAt: '2026-09-26T00:00:00Z',
  updatedAt: '2026-09-26T00:00:00Z',
};

const projectPayload = { success: true, data: { items: [readyProject], nextCursor: null } };

/** capabilities DTO：版本来自发行版 manifest；runner 离线 → feature.ready=false。 */
const capabilitiesEnabled = {
  success: true,
  data: {
    enabled: true,
    version: '0.2.16',
    features: [
      { id: 'engine', installed: true, configured: true, prepared: true, ready: true, reason: null, action: null },
      { id: 'runner', installed: true, configured: true, prepared: false, ready: false, reason: 'runner daemon 未完成握手', action: 'runner 容器未就绪时构建/编译暂不可用' },
      { id: 'studio', installed: false, configured: true, prepared: false, ready: false, reason: 'Studio 会话能力由 C107F2-19 起接线', action: null },
    ],
    templates: [],
  },
};

const capabilitiesDisabled = {
  success: true,
  data: {
    enabled: false,
    version: null,
    features: [
      { id: 'engine', installed: false, configured: false, prepared: false, ready: false, reason: 'HYPIT_ENABLED=false', action: '联系部署者启用 Hypit' },
    ],
    templates: [],
  },
};

function respond(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });
}

function installFetch(routes: Record<string, () => Response>): { calls: { url: string }[] } {
  const calls: { url: string }[] = [];
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input);
    calls.push({ url });
    for (const [pattern, factory] of Object.entries(routes)) {
      if (url.includes(pattern)) return factory();
    }
    return respond(404, { success: false, error: 'not found', code: 'hypit_not_found' });
  }));
  return { calls };
}

function projectRequestCount(calls: { url: string }[]): number {
  return calls.filter((call) => call.url.includes('/api/hypit/projects')).length;
}

async function mountWorkbench(): Promise<ReturnType<typeof mount>> {
  const VideoCloneWorkbench = (await import('../VideoCloneWorkbench.vue')).default;
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/video-clone/:projectId?', name: 'video-clone', component: VideoCloneWorkbench },
      { path: '/', redirect: '/video-clone' },
    ],
  });
  await router.push('/video-clone');
  await router.isReady();
  return mount(VideoCloneWorkbench, {
    global: { plugins: [router], stubs: { teleport: true } },
    attachTo: document.body,
  });
}

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  document.body.innerHTML = '';
});

describe('C107F2-09 真实 readiness 与工作区顶层状态', () => {
  test('TC-F2-09-01 disabled 入口：显示未启用、projects 请求 0、不无限加载', async () => {
    const { calls } = installFetch({
      '/api/hypit/capabilities': () => respond(200, capabilitiesDisabled),
    });
    const wrapper = await mountWorkbench();
    await flushPromises();

    const disabled = wrapper.find('[data-testid="clone-runtime-disabled"]');
    expect(disabled.exists()).toBe(true);
    expect(disabled.text()).toContain('未启用');
    // 禁用态不出现可执行创建按钮（ProjectList 整体未渲染）。
    expect(wrapper.find('[data-testid="clone-project-list"]').exists()).toBe(false);
    expect(wrapper.find('[data-testid="clone-new-project"]').exists()).toBe(false);
    // 不出现无限加载：checking 已结束，页面终态可读。
    expect(wrapper.find('[data-testid="clone-runtime-checking"]').exists()).toBe(false);
    // 反向防假阳性：projects 网络请求必须为 0（数据层闸门拦截，而非服务端 404 兜底）。
    expect(projectRequestCount(calls)).toBe(0);
    wrapper.unmount();
  });

  test('TC-F2-09-02 实际版本：capabilities/doctor 读取 0.2.16；runner 离线 feature.ready=false', async () => {
    installFetch({
      '/api/hypit/capabilities': () => respond(200, capabilitiesEnabled),
    });
    // RuntimePanel 是 capabilities/doctor 事实的消费面（读真实端点，非注入值）。
    const RuntimePanel = (await import('../components/RuntimePanel.vue')).default;
    const wrapper = mount(RuntimePanel, { props: { projectReady: true }, attachTo: document.body });
    await flushPromises();

    const version = wrapper.find('[data-testid="clone-runtime-version"]');
    expect(version.exists()).toBe(true);
    expect(version.text()).toContain('0.2.16');
    // runner 离线：对应 feature 就绪位为 false 且 reason 可见，不虚构就绪。
    const features = wrapper.findAll('.clone-runtime-feature');
    const runnerFeature = features.map((feature) => feature.text()).find((text) => text.includes('runner'));
    expect(runnerFeature).toBeDefined();
    expect(runnerFeature).toContain('未就绪');
    // 同一数据源下 studio 未接线也如实未就绪（禁止全绿假象）。
    const studioFeature = features.map((feature) => feature.text()).find((text) => text.includes('studio'));
    expect(studioFeature).toContain('未就绪');
    wrapper.unmount();
  });

  test('TC-F2-09-03 会话失效：复用登录入口、旧账号数据清空、不显示成功空列表', async () => {
    let expired = false;
    installFetch({
      '/api/hypit/capabilities': () => respond(expired ? 401 : 200, expired
        ? { success: false, error: { message: '登录已过期', code: 'hypit_unauthenticated' } }
        : capabilitiesEnabled),
      '/api/hypit/projects?': () => respond(200, projectPayload),
    });

    // 组件层：列表已加载 → 登录过期 → 刷新（重挂载）后进入未认证终态。
    const first = await mountWorkbench();
    await flushPromises();
    expect(first.text()).toContain('榜单复刻');
    first.unmount();

    expired = true;
    const second = await mountWorkbench();
    await flushPromises();
    const login = second.find('[data-testid="clone-runtime-login"]');
    expect(login.exists()).toBe(true);
    expect(login.text()).toContain('登录已过期');
    // 旧账号数据清空：工程标题不以成功列表形态出现。
    expect(second.text()).not.toContain('榜单复刻');
    // 不显示成功空列表：未认证态不渲染 ProjectList。
    expect(second.find('[data-testid="clone-project-list"]').exists()).toBe(false);
    second.unmount();

    // composable 层：同实例探测 401 → onAccountReset 清旧账号私有状态（真实 probe）。
    // 先恢复会话有效，探测回到 ready 后再制造 401（阶段变量复用需复位）。
    expired = false;
    const resets = vi.fn();
    const scope = effectScope();
    const runtime = scope.run(() => useHypitRuntime({ onAccountReset: resets }))!;
    await runtime.probe();
    expect(runtime.status.value).toBe('ready');
    expect(resets).not.toHaveBeenCalled();
    expired = true;
    await runtime.probe();
    expect(runtime.status.value).toBe('unauthenticated');
    expect(resets).toHaveBeenCalledTimes(1);
    scope.stop();
  });

  test('TC-F2-09-04 恢复重试：初次 broker 不可达随后恢复，重试后只拉取一次列表', async () => {
    let brokerDown = true;
    const { calls } = installFetch({
      '/api/hypit/capabilities': () => (brokerDown
        ? respond(503, { success: false, error: { message: 'hypit 后端不可达', code: 'hypit_backend_unavailable' } })
        : respond(200, capabilitiesEnabled)),
      '/api/hypit/projects?': () => respond(200, projectPayload),
    });
    const wrapper = await mountWorkbench();
    await flushPromises();

    // 初次不可达：显示 unavailable 与重试按钮，无工程数据、无 projects 请求。
    const unavailable = wrapper.find('[data-testid="clone-runtime-unavailable"]');
    expect(unavailable.exists()).toBe(true);
    expect(projectRequestCount(calls)).toBe(0);
    expect(wrapper.text()).not.toContain('榜单复刻');

    // broker 恢复后点击重试。
    brokerDown = false;
    await unavailable.find('button').trigger('click');
    await flushPromises();

    // 页面可操作：工程列表出现，unavailable 消失。
    expect(wrapper.text()).toContain('榜单复刻');
    expect(wrapper.find('[data-testid="clone-runtime-unavailable"]').exists()).toBe(false);
    // 恢复后只拉取一次列表（初始自动拉取被闸门挡下，onReady 只补拉一次）。
    expect(projectRequestCount(calls)).toBe(1);
    wrapper.unmount();
  });
});
