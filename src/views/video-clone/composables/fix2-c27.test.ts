// @vitest-environment happy-dom
// fix2-c27.test.ts — C107F2-27（TC-F2-27-01～04）：变体前端真实取消、重试、
// 授权与状态展示。composable 层：mock HTTP（fetch 替身）跑真实 useHypitVariants，
// 断言请求序列（一次 POST cancel 而非仅 GET）、幂等键稳定性（重试超时重放同
// requestId）、授权语义（取消授权零 build 请求）与部分失败作用域。
import { afterEach, describe, expect, test, vi } from 'vitest';
import { effectScope } from 'vue';
import { useHypitVariants } from './useHypitVariants';
import type { HypitVariantItem } from '../../../types/hypit';

const PROJECT = '47474747-4747-4747-8474-474747474747';

function respond(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });
}

interface Call { url: string; method: string; body: Record<string, unknown> }

function installFetch(handler: (call: Call) => Response | Promise<Response>): Call[] {
  const calls: Call[] = [];
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    const body = init?.body ? JSON.parse(String(init.body)) as Record<string, unknown> : {};
    const call: Call = { url, method: init?.method ?? 'GET', body };
    calls.push(call);
    return handler(call);
  }));
  return calls;
}

function variant(input: Partial<HypitVariantItem> & { id: string; ordinal: number }): HypitVariantItem {
  return {
    batchJobId: 'bbbbbbbb-0000-4000-8000-00000000000b',
    runFile: 'runs/variants/variant-0.svrun',
    state: 'queued',
    attempt: 1,
    parameters: {},
    ...input,
  };
}

function ok(body: unknown): Response {
  return respond({ success: true, data: body });
}

afterEach(() => vi.unstubAllGlobals());

describe('C107F2-27 useHypitVariants', () => {
  test('TC-F2-27-01 取消是一次 POST cancel：最终状态按服务端回执', async () => {
    const target = variant({ id: 'vvvvvvvv-0000-4000-8000-000000000001', ordinal: 0, state: 'running' });
    const calls = installFetch(({ url, method }) => {
      if (method === 'POST' && url.endsWith(`/variants/${target.id}/cancel`)) {
        // 服务端说了算：本地原本 running，回执 cancelled。
        return ok({ id: target.id, state: 'cancelled', attempt: 1 });
      }
      if (method === 'GET' && url.includes('/variants?')) return ok({ items: [target] });
      return respond({ success: false, error: { code: 'hypit_not_found', message: 'unexpected' } }, 404);
    });
    const scope = effectScope();
    const variants = scope.run(() => useHypitVariants())!;
    await variants.refresh(PROJECT);
    expect(variants.items.value[0]!.state).toBe('running');

    const outcome = await variants.act(PROJECT, variants.items.value[0]!, 'cancel');
    expect(outcome).toBe('applied');
    // 恰一次 POST cancel；且它发生在任何 GET 之前（不是 refresh 推断）。
    const cancels = calls.filter((call) => call.url.endsWith('/cancel'));
    expect(cancels).toHaveLength(1);
    expect(cancels[0]!.method).toBe('POST');
    expect(cancels[0]!.body.requestId).toBeTruthy();
    // act 不以 refresh 代替 mutation：除首次装载外无额外 GET。
    expect(calls.filter((call) => call.method === 'GET')).toHaveLength(1);
    // 最终状态按服务端：running → cancelled。
    expect(variants.items.value[0]!.state).toBe('cancelled');
    expect(variants.error.value).toBeNull();
    scope.stop();
  });

  test('TC-F2-27-02 重试超时重放同 requestId；409 状态闸后 attempt 保持 2 不变 3', async () => {
    const target = variant({ id: 'vvvvvvvv-0000-4000-8000-000000000002', ordinal: 0, state: 'failed' });
    let retryCalls = 0;
    const calls = installFetch(({ url, method }) => {
      if (method === 'POST' && url.endsWith(`/variants/${target.id}/retry`)) {
        retryCalls += 1;
        if (retryCalls === 1) {
          // 模拟网络超时：请求失败（连接中断）。
          return Promise.reject(new TypeError('模拟网络超时'));
        }
        if (retryCalls === 2) return ok({ id: target.id, state: 'queued', attempt: 2 });
        // 同键第三次到达服务端：状态闸 409（重复键不 +2）。
        return respond({ success: false, error: { code: 'hypit_state_conflict', message: '只有 failed/cancelled 变体可重试' } }, 409);
      }
      if (method === 'POST' && url.endsWith('/plan')) return ok({ plan: { id: 'retry-plan' }, providers: [] });
      if (method === 'POST' && url.endsWith('/pricing')) return ok({ planId: 'retry-plan', pricingId: 'retry-price', rows: [] });
      if (method === 'POST' && url.endsWith('/build')) return ok({ buildId: 'retry-build' });
      return respond({ success: false, error: { code: 'hypit_not_found', message: 'unexpected' } }, 404);
    });
    const scope = effectScope();
    const variants = scope.run(() => useHypitVariants())!;
    variants.items.value = [target];

    // 第一次点击：超时——错误如实展示，输入保留。
    await variants.act(PROJECT, target, 'retry');
    expect(variants.error.value).toContain('超时');
    // 重放（attempt 仍 1 → 同键）。
    const outcome = await variants.act(PROJECT, variants.items.value[0]!, 'retry');
    expect(outcome).toBe('submitted');
    expect(variants.items.value[0]!.buildId).toBe('retry-build');
    expect(variants.error.value).toBeNull();
    expect(variants.items.value[0]!.attempt).toBe(2);
    expect(variants.items.value[0]!.state).toBe('queued');
    const firstKey = calls[0]!.body.requestId;
    expect(calls[1]!.body.requestId).toBe(firstKey);
    // 已 queued 后再点重试：服务端 409——回执不落项，attempt 保持 2。
    await variants.act(PROJECT, variants.items.value[0]!, 'retry');
    expect(calls.filter(call => call.url.endsWith('/retry'))[2]!.body.requestId).not.toBe(firstKey);
    // 重复重试不得增长 attempt（409 状态闸，回执不落项）。
    expect(variants.items.value[0]!.attempt).toBe(2);
    variants.reset();
    scope.stop();
  });

  test('TC-F2-27-03 远程变体取消授权零 build 请求；确认后恰一次 grant+build', async () => {
    const target = variant({ id: 'vvvvvvvv-0000-4000-8000-000000000003', ordinal: 1, runFile: 'runs/variants/variant-1.svrun' });
    const calls = installFetch(({ url, method }) => {
      if (method === 'POST' && url.endsWith('/plan')) return ok({ plan: { id: 'plan-9' }, providers: [] });
      if (method === 'POST' && url.endsWith('/pricing')) {
        return ok({ pricingId: 'pricing-9', planId: 'plan-9', rows: [
          { need: 'media.generate', provider: 'seedance-2-mini', currency: 'USD', unitAmount: '0.25', known: true },
        ], knownCosts: {}, unknownRequests: [] });
      }
      if (method === 'POST' && url.endsWith('/execution-grants')) return ok({ grantId: 'grant-9' });
      if (method === 'POST' && url.endsWith(`/variants/${target.id}/build`)) return ok({ buildId: 'build-9', lifecycle: 'active' });
      return respond({ success: false, error: { code: 'hypit_not_found', message: 'unexpected' } }, 404);
    });
    const scope = effectScope();
    const variants = scope.run(() => useHypitVariants())!;
    variants.items.value = [target];

    // 点击构建：报价后停 in 授权对话框（pendingGrant），零 build。
    let outcome = await variants.act(PROJECT, target, 'build');
    expect(outcome).toBe('awaiting-grant');
    expect(variants.pendingGrant.value).not.toBeNull();
    expect(variants.pendingGrant.value!.needs[0]).toContain('seedance-2-mini');
    expect(variants.pendingGrant.value!.maxAmount).toBe('0.25');
    expect(calls.filter((call) => call.url.endsWith('/build'))).toHaveLength(0);
    // 取消授权：零 grant/build，变体保留。
    variants.dismissGrant();
    expect(variants.pendingGrant.value).toBeNull();
    expect(calls.filter((call) => call.url.endsWith('/execution-grants') || call.url.endsWith('/build'))).toHaveLength(0);
    expect(variants.items.value[0]!.state).toBe('queued');

    // 再次构建 → 确认：恰一次 grant + 一次 build（grantId 入参）。
    outcome = await variants.act(PROJECT, variants.items.value[0]!, 'build');
    expect(outcome).toBe('awaiting-grant');
    await variants.confirmGrant(PROJECT);
    const grants = calls.filter((call) => call.url.endsWith('/execution-grants'));
    const builds = calls.filter((call) => call.url.endsWith('/build'));
    expect(grants).toHaveLength(1);
    expect(builds).toHaveLength(1);
    expect(builds[0]!.body.grantId).toBe('grant-9');
    expect(builds[0]!.body.requestId).toBeTruthy();
    expect(variants.items.value[0]!.buildId).toBe('build-9');
    expect(variants.error.value).toBeNull();
    scope.stop();
  });

  test('TC-F2-27-04 刷新世代防串写；重试只落失败项，成功/运行项不动', async () => {
    const stale = [
      variant({ id: 'vvvvvvvv-0000-4000-8000-0000000000a1', ordinal: 0, state: 'failed' }),
      variant({ id: 'vvvvvvvv-0000-4000-8000-0000000000a2', ordinal: 1, state: 'running' }),
    ];
    const fresh = [
      variant({ id: 'vvvvvvvv-0000-4000-8000-0000000000a0', ordinal: 0, state: 'succeeded', buildId: undefined }),
      variant({ id: 'vvvvvvvv-0000-4000-8000-0000000000a1', ordinal: 1, state: 'failed' }),
      variant({ id: 'vvvvvvvv-0000-4000-8000-0000000000a2', ordinal: 2, state: 'running' }),
    ];
    let listCalls = 0;
    let releaseStale: ((response: Response) => void) | null = null;
    const stalePromise = new Promise<Response>((resolve) => { releaseStale = resolve; });
    const calls = installFetch(({ url, method }) => {
      if (method === 'GET' && url.includes('/variants?')) {
        listCalls += 1;
        if (listCalls === 1) return stalePromise;
        return Promise.resolve(ok({ items: fresh }));
      }
      if (method === 'POST' && url.endsWith(`/variants/${fresh[1]!.id}/retry`)) {
        return ok({ id: fresh[1]!.id, state: 'queued', attempt: 2 });
      }
      if (method === 'POST' && url.endsWith('/plan')) return ok({ plan: { id: 'retry-plan' }, providers: [] });
      if (method === 'POST' && url.endsWith('/pricing')) return ok({ planId: 'retry-plan', pricingId: 'retry-price', rows: [] });
      if (method === 'POST' && url.endsWith('/build')) return ok({ buildId: 'retry-build' });
      return respond({ success: false, error: { code: 'hypit_not_found', message: 'unexpected' } }, 404);
    });
    const scope = effectScope();
    const variants = scope.run(() => useHypitVariants())!;

    // 两次刷新：第一次慢（旧列表 2 项），第二次快（新列表 3 项）——旧响应不得串写。
    const first = variants.refresh(PROJECT);
    await variants.refresh(PROJECT);
    releaseStale!(ok({ items: stale }));
    await Promise.all([first, new Promise((resolve) => setTimeout(resolve, 0))]);
    expect(variants.items.value).toHaveLength(3);
    expect(variants.items.value.map((item) => item.id)).toEqual(fresh.map((item) => item.id));

    // 只操作失败项：mutation 落在该项；成功项 build 关联保留、运行项未被取消。
    const failed = variants.items.value[1]!;
    await variants.act(PROJECT, failed, 'retry');
    expect(variants.items.value[1]!.state).toBe('queued');
    expect(variants.items.value[1]!.attempt).toBe(2);
    expect(variants.items.value[0]!.state).toBe('succeeded');
    expect(variants.items.value[2]!.state).toBe('running');
    expect(calls.filter((call) => call.url.endsWith('/cancel'))).toHaveLength(0);
    expect(calls.filter((call) => call.url.endsWith('/retry'))).toHaveLength(1);
    variants.reset();
    scope.stop();
  });
});
