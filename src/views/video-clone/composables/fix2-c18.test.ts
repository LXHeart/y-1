// @vitest-environment happy-dom
// fix2-c18.test.ts — C107F2-18（TC-F2-18-01～04）：分析、方案、生成、重试、取消与授权 UI
// 工作流。composable 层：mock HTTP（fetch 替身）跑真实 useHypitWorkflow，断言请求序列、
// 幂等键稳定性与授权/取消语义；不 mock 被测编排函数。
import { afterEach, describe, expect, test, vi } from 'vitest';
import { effectScope } from 'vue';
import { useHypitWorkflow } from './useHypitWorkflow';

const PROJECT = '44444444-4444-4444-8444-444444444444';
const JOB_ID = '55555555-5555-4555-8555-555555555555';
const BUILD_JOB_ID = '66666666-6666-4666-8666-666666666666';

function respond(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });
}

interface Call { url: string; method: string; body: Record<string, unknown> }

function installFetch(handler: (call: Call) => Response | Promise<Response>): Call[] {
  const calls: Call[] = [];
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    const body = init?.body ? JSON.parse(String(init.body)) as Record<string, unknown> : {};
    calls.push({ url, method: init?.method ?? 'GET', body });
    return handler({ url, method: init?.method ?? 'GET', body });
  }));
  return calls;
}

function noRemoteNeed(): (call: Call) => Response {
  return ({ url }) => {
    if (url.endsWith('/check')) return respond({ success: true, data: { ok: true, diagnostics: [] } });
    if (url.endsWith('/plan')) return respond({ success: true, data: { plan: { id: 'plan-1', planHash: 'p'.repeat(16), revision: 2, runFile: 'main.svrun' }, providers: [] } });
    if (url.endsWith('/pricing')) return respond({ success: true, data: { pricingId: 'pricing-1', planId: 'plan-1', rows: [], knownCosts: {}, unknownRequests: [] } });
    if (url.endsWith('/builds')) return respond({ success: true, data: { build: { id: 'build-1' }, job: { id: BUILD_JOB_ID } } });
    return respond({ success: true, data: {} }, 404);
  };
}

afterEach(() => vi.unstubAllGlobals());

describe('C107F2-18 useHypitWorkflow', () => {
  test('TC-F2-18-01 本地计划生成：POST plan/pricing/build 实际发生并显示 job', async () => {
    const accepted: string[] = [];
    const calls = installFetch(noRemoteNeed());
    const scope = effectScope();
    const workflow = scope.run(() => useHypitWorkflow({
      onJobAccepted: (jobId) => accepted.push(jobId),
    }))!;
    const outcome = await workflow.generate(PROJECT, { revision: 2 });
    expect(outcome).toBe('submitted');

    const posts = calls.filter((call) => call.method === 'POST').map((call) => call.url);
    expect(posts.some((url) => url.endsWith('/check'))).toBe(true);
    expect(posts.some((url) => url.endsWith('/plan'))).toBe(true);
    expect(posts.some((url) => url.endsWith('/pricing'))).toBe(true);
    expect(posts.some((url) => url.endsWith('/builds'))).toBe(true);
    expect(accepted).toEqual([BUILD_JOB_ID]);
    expect(workflow.job.value).toEqual({ jobId: BUILD_JOB_ID, state: 'running' });
    expect(workflow.error.value).toBeNull();
    scope.stop();
  });

  test('TC-F2-18-02 远程 Need：取消授权零提交；确认后 grant/build 恰一次', async () => {
    const calls = installFetch(({ url }) => {
      if (url.endsWith('/check')) return respond({ success: true, data: { ok: true, diagnostics: [] } });
      if (url.endsWith('/plan')) return respond({ success: true, data: { plan: { id: 'plan-2', planHash: 'q'.repeat(16), revision: 3, runFile: 'main.svrun' }, providers: [{ status: 'resolved' }] } });
      if (url.endsWith('/pricing')) return respond({ success: true, data: { pricingId: 'pricing-2', planId: 'plan-2', rows: [{ need: 'video.clone', provider: 'seedance-2-mini', currency: 'USD', unitAmount: '0.50', known: true }], knownCosts: { 'video.clone': '0.50' }, unknownRequests: [] } });
      if (url.endsWith('/execution-grants')) return respond({ success: true, data: { grantId: 'grant-1' } });
      if (url.endsWith('/builds')) return respond({ success: true, data: { build: { id: 'build-2' }, job: { id: BUILD_JOB_ID } } });
      return respond({ success: true, data: {} }, 404);
    });
    const scope = effectScope();
    const workflow = scope.run(() => useHypitWorkflow())!;

    const outcome = await workflow.generate(PROJECT, { revision: 3 });
    expect(outcome).toBe('awaiting-grant');
    expect(workflow.pendingGrant.value).not.toBeNull();
    expect(workflow.pendingGrant.value!.needs[0]).toContain('seedance-2-mini');
    expect(workflow.pendingGrant.value!.maxAmount).toBe('0.50');
    // 取消授权对话框：零 grant/build。
    workflow.dismissGrant();
    expect(workflow.pendingGrant.value).toBeNull();
    expect(calls.filter((call) => call.url.endsWith('/execution-grants') || call.url.endsWith('/builds'))).toHaveLength(0);

    // 确认后一次提交：grant 1 次 + build 1 次。
    await workflow.generate(PROJECT, { revision: 3 });
    await workflow.confirmGrant(PROJECT, { revision: 3 });
    expect(calls.filter((call) => call.url.endsWith('/execution-grants'))).toHaveLength(1);
    expect(calls.filter((call) => call.url.endsWith('/builds'))).toHaveLength(1);
    scope.stop();
  });

  test('TC-F2-18-03 取消：cancel API 一次（连点合并），申请中并观察服务端终态', async () => {
    const terminals: string[] = [];
    const calls = installFetch(({ url }) => {
      if (url.includes('/actions')) return respond({ success: true, data: { state: 'cancelled', accepted: 'cancel' } });
      if (url.endsWith('/agent-jobs')) return respond({ success: true, data: { jobId: JOB_ID, state: 'queued' } });
      return respond({ success: true, data: { ok: true } });
    });
    const scope = effectScope();
    const workflow = scope.run(() => useHypitWorkflow({
      watchJob: (_projectId, jobId, onTerminal) => { onTerminal?.({ state: 'cancelled' }); terminals.push(jobId); },
    }))!;
    await workflow.analyze(PROJECT, { brief: '分析' });
    expect(workflow.job.value?.jobId).toBe(JOB_ID);

    // 连点两次取消：在途合并为一次 API。
    await Promise.all([workflow.cancel(PROJECT, JOB_ID), workflow.cancel(PROJECT, JOB_ID)]);
    const cancelCalls = calls.filter((call) => call.url.includes('/actions'));
    expect(cancelCalls).toHaveLength(1);
    expect(cancelCalls[0].body.action).toBe('cancel');
    // 观察到服务端 cancelled 终态（onTerminal 回调驱动），取消申请中复位。
    await Promise.resolve();
    expect(terminals).toContain(JOB_ID);
    scope.stop();
  });

  test('TC-F2-18-04 提交超时重试同 requestId：恢复同 job，不新建重复任务', async () => {
    let buildAttempts = 0;
    const calls = installFetch(({ url }) => {
      if (url.endsWith('/check')) return respond({ success: true, data: { ok: true, diagnostics: [] } });
      if (url.endsWith('/plan')) return respond({ success: true, data: { plan: { id: 'plan-3', planHash: 'r'.repeat(16), revision: 4, runFile: 'main.svrun' }, providers: [] } });
      if (url.endsWith('/pricing')) return respond({ success: true, data: { pricingId: 'pricing-3', planId: 'plan-3', rows: [], knownCosts: {}, unknownRequests: [] } });
      if (url.endsWith('/builds')) {
        buildAttempts += 1;
        if (buildAttempts === 1) throw new TypeError('network timeout');
        return respond({ success: true, data: { build: { id: 'build-3' }, job: { id: BUILD_JOB_ID } } });
      }
      return respond({ success: true, data: {} }, 404);
    });
    const scope = effectScope();
    const workflow = scope.run(() => useHypitWorkflow())!;

    await expect(workflow.generate(PROJECT, { revision: 4 })).rejects.toThrow('network timeout');
    // 错误后输入/计划保留（不清 plan），重试（刷新后同参数）。
    expect(workflow.plan.value?.id).toBe('plan-3');
    await workflow.generate(PROJECT, { revision: 4 });

    const requestIds = (stage: string) => calls
      .filter((call) => call.url.endsWith(`/${stage === 'build' ? 'builds' : stage}`))
      .map((call) => String(call.body.requestId));
    // plan/pricing/build 每阶段的两次尝试（重试前后）requestId 完全一致——参数未变不换键。
    for (const stage of ['plan', 'pricing', 'build']) {
      const ids = requestIds(stage);
      expect(ids.length).toBeGreaterThanOrEqual(1);
      // 稳定幂等键：重试前后同键（Set 尺寸恒 1）——参数变更才换键。
      expect(new Set(ids).size).toBe(1);
    }
    // 服务端按同 requestId 幂等：恢复同一 build/job，不新建重复任务。
    expect(workflow.job.value?.jobId).toBe(BUILD_JOB_ID);
    scope.stop();
  });
});

// ── C107F3-10（TC-F3-10-04）：可信再生成 UI 生命周期及重试 ────────────────────
// composable 层真实验证：fetch 替身跑真实 useHypitWorkflow.regeneratePlan，断言
// 新标志透传、连点合并、同 ID 重试/新一次换 ID、终态回调与 generation gate。
describe('C107F3-10 regeneratePlan 生命周期', () => {
  const accepted = (): Response => respond({ success: true, data: { jobId: JOB_ID, state: 'queued', resourceId: null } });

  test('TC-F3-10-04 新标志透传：仅 true 进请求体；旧调用保持旧形态', async () => {
    const calls = installFetch(({ url }) => url.endsWith('/agent-jobs') ? accepted() : respond({ success: true, data: {} }, 404));
    const scope = effectScope();
    const workflow = scope.run(() => useHypitWorkflow())!;

    await workflow.regeneratePlan(PROJECT, { brief: '按最新分析与素材重新生成方案', baseRevision: 2, regenerateFromLatestAnalysis: true });
    const withFlag = calls[calls.length - 1];
    expect(withFlag.body).toMatchObject({ intent: 'author', assetIds: [], baseRevision: 2, regenerateFromLatestAnalysis: true });
    expect(withFlag.body.regenerateFromLatestAnalysis).toBe(true);

    // 旧路径（未传标志）：请求体不得出现新字段（旧字节形态不变，服务端旧 hash 兼容）。
    await workflow.regeneratePlan(PROJECT, { brief: '按最新分析与素材重新生成方案', baseRevision: 3 });
    const legacy = calls[calls.length - 1];
    expect(legacy.body).not.toHaveProperty('regenerateFromLatestAnalysis');
    expect(legacy.body).toMatchObject({ intent: 'author', baseRevision: 3 });
    scope.stop();
  });

  test('TC-F3-10-04 连点只发一个请求；受理后 running，终态回调置 succeeded 并透出 onTerminal', async () => {
    let release: ((value: Response) => void) | null = null;
    const first = new Promise<Response>((resolvePromise) => { release = resolvePromise; });
    const calls = installFetch(() => first);
    const terminals: Array<{ state: string }> = [];
    let fireTerminal: ((job: { state: string }) => void) | null = null;
    const scope = effectScope();
    const workflow = scope.run(() => useHypitWorkflow({
      watchJob: (_projectId, _jobId, onTerminal) => { fireTerminal = onTerminal ?? null; },
    }))!;

    // 连点：第一次受理挂起期间第二次直接放弃（不发第二个 POST）。
    const inFlight = workflow.regeneratePlan(PROJECT, { brief: '重生成', baseRevision: 2, regenerateFromLatestAnalysis: true, onTerminal: (job) => terminals.push(job) });
    await workflow.regeneratePlan(PROJECT, { brief: '重生成', baseRevision: 2, regenerateFromLatestAnalysis: true });
    release!(accepted());
    await inFlight;
    expect(calls.filter((call) => call.url.endsWith('/agent-jobs'))).toHaveLength(1);
    expect(workflow.regenerateStatus.value).toBe('running'); // 202 不展示成功

    // 服务端终态（真实 job 观察回调）→ succeeded + onTerminal 透出（终态才重读的钩子）。
    fireTerminal!({ state: 'succeeded' });
    expect(workflow.regenerateStatus.value).toBe('succeeded');
    expect(terminals).toEqual([{ state: 'succeeded' }]);
    scope.stop();
  });

  test('TC-F3-10-04 网络不确定重试同 ID；明确拒绝后新一次生成换新 ID', async () => {
    let attempt = 0;
    const calls = installFetch(() => {
      attempt += 1;
      if (attempt === 1) throw new TypeError('network interrupted');
      if (attempt === 2) return respond({ success: false, error: { code: 'hypit_state_conflict', message: '工程已更新（baseRevision=2 ≠ head=3），请刷新后重试' } }, 409);
      return accepted();
    });
    const scope = effectScope();
    const workflow = scope.run(() => useHypitWorkflow())!;

    // 网络中断（结果未知）：重试复用同 requestId（服务端幂等恢复同一 job）。
    await expect(workflow.regeneratePlan(PROJECT, { brief: '重生成', baseRevision: 2, regenerateFromLatestAnalysis: true })).rejects.toThrow('network interrupted');
    await expect(workflow.regeneratePlan(PROJECT, { brief: '重生成', baseRevision: 2, regenerateFromLatestAnalysis: true })).rejects.toThrow('工程已更新');
    const ids = calls.map((call) => String(call.body.requestId));
    expect(new Set([ids[0], ids[1]]).size).toBe(1); // 网络不确定 → 同 ID

    // 服务端明确 409（结果已定）：用户再点是新一次生成 → 换新 ID。
    await workflow.regeneratePlan(PROJECT, { brief: '重生成', baseRevision: 3, regenerateFromLatestAnalysis: true });
    expect(calls[2].body.requestId).not.toBe(ids[0]);
    scope.stop();
  });

  test('TC-F3-10-04 generation gate：切工程/换号/卸载后旧回调（成功/终态/finally）不改新页面', async () => {
    let fireTerminal: ((job: { state: string }) => void) | null = null;
    const calls = installFetch(({ url }) => url.endsWith('/agent-jobs') ? accepted() : respond({ success: true, data: {} }, 404));
    const scope = effectScope();
    const workflow = scope.run(() => useHypitWorkflow({
      watchJob: (_projectId, _jobId, onTerminal) => { fireTerminal = onTerminal ?? null; },
    }))!;

    // A 工程受理（同一实例，真实视图每次 regeneratePlan 重新装配观察）。
    await workflow.regeneratePlan(PROJECT, { brief: '重生成', baseRevision: 2, regenerateFromLatestAnalysis: true });
    expect(workflow.regenerateStatus.value).toBe('running');
    const staleTerminal = fireTerminal!;
    // A→B：切工程使 A 的所有回调失效并复位状态（不清 B 的在途保护）。
    workflow.invalidateRegenerate();
    expect(workflow.regenerateStatus.value).toBe('idle');

    // B 开始新一次生成；随后 A 的延迟终态到达（同一实例上的旧闭包）。
    const terminalsB: Array<{ state: string }> = [];
    await workflow.regeneratePlan(PROJECT, { brief: '重生成B', baseRevision: 5, regenerateFromLatestAnalysis: true, onTerminal: (job) => terminalsB.push(job) });
    expect(workflow.regenerateStatus.value).toBe('running');
    staleTerminal({ state: 'succeeded' });
    expect(workflow.regenerateStatus.value).toBe('running'); // 旧回调不改新页
    expect(terminalsB).toEqual([]); // 旧终态不触发新页面的重读
    // B 自己的终态才生效。
    fireTerminal!({ state: 'succeeded' });
    expect(workflow.regenerateStatus.value).toBe('succeeded');
    expect(terminalsB).toEqual([{ state: 'succeeded' }]);
    expect(calls.filter((call) => call.url.endsWith('/agent-jobs'))).toHaveLength(2);
    scope.stop();
  });

  test('TC-F3-10-04 失败保留：请求错误置 failed 与原始 message（方案/输入由视图层保留）', async () => {
    installFetch(() => respond({ success: false, error: { code: 'hypit_state_conflict', message: '当前工程没有可信的完整参考分析' } }, 409));
    const scope = effectScope();
    const workflow = scope.run(() => useHypitWorkflow())!;
    await expect(workflow.regeneratePlan(PROJECT, { brief: '重生成', baseRevision: 2, regenerateFromLatestAnalysis: true }))
      .rejects.toThrow('没有可信的完整参考分析');
    expect(workflow.regenerateStatus.value).toBe('failed');
    expect(workflow.regenerateError.value?.status).toBe(409);
    expect(workflow.regenerateError.value?.message).toContain('没有可信的完整参考分析');
    // 旧回调不残留：错误后 gate 已前移（新一次生成不受旧状态影响）。
    await workflow.regeneratePlan(PROJECT, { brief: '重生成', baseRevision: 2, regenerateFromLatestAnalysis: true }).catch(() => undefined);
    expect(workflow.regenerateStatus.value).toBe('failed');
    scope.stop();
  });
});
