/**
 * useHypitWorkflow.ts — C107F2-18（W128 / F06）：分析、方案、生成、重试、取消与授权的
 * 工作流编排。视图只组合本 composable，不再 void 0 或只切页签。
 *
 * 流映射（§6.7/卡步骤 1）：
 * - analyze / regeneratePlan → POST agent-jobs（intent=analyze / author），accepted 后 watchJob；
 * - generate → check → plan → pricing →（远程 Need 时）授权对话框 → execution-grants → builds，
 *   accepted 后 jobId 进 URL 并订阅（useHypitJobs）；
 * - 授权语义（步骤 2）：取消对话框即放弃（零 grant/build 调用），显式确认才一次提交；
 * - 重试稳定键（步骤 3/TC-04）：plan/pricing/submit 的 requestId 派生自
 *   (projectId, runFile, revision)——参数变更才新键，提交超时按同键重试恢复同一 build/job；
 * - 取消（步骤 4）：显示「取消申请中」并继续观察服务端终态；在途连点只发一个动作。
 * 被测面为真实 fetch（hypotRequest 网关），组件测试以 fetch 替身断言请求序列。
 */
import { computed, ref } from 'vue';
import { hypitRequest } from './hypit-api';
import type { RefreshGate } from './useHypitProjectScope';

interface PlanRow { id: string; planHash: string; revision: number; runFile: string }
interface PricingView { pricingId: string; planId: string; rows: Array<Record<string, unknown>>; knownCosts: Record<string, string>; unknownRequests: Array<Record<string, unknown>> }
interface GrantQuoteRow { need: string; provider: string; currency: string; unitAmount: string | null; known: boolean }
interface JobReceipt { jobId: string; state: string }

/** 稳定幂等键：同 (project, runFile, revision, stage) 恒同值；参数变更自然换键（TC-04）。 */
function stableKey(projectId: string, runFile: string, revision: number, stage: string): string {
  const raw = `${projectId}|${runFile}|${revision}|${stage}`;
  let h1 = 0x811c9dc5;
  for (let i = 0; i < raw.length; i += 1) {
    h1 ^= raw.charCodeAt(i);
    h1 = Math.imul(h1, 0x01000193) >>> 0;
  }
  const hex = h1.toString(16).padStart(8, '0').repeat(4);
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-4${hex.slice(13, 16)}-8${hex.slice(17, 20)}-${hex.slice(20, 32)}`;
}

export function useHypitWorkflow(
  observers: {
    onJobAccepted?: (jobId: string, intent?: string) => void;
    watchJob?: (projectId: string, jobId: string, onTerminal?: (job: { state: string }) => void) => void;
  } = {},
) {
  const phase = ref<'idle' | 'analyzing' | 'planning' | 'pricing' | 'awaiting-grant' | 'submitting' | 'cancelling'>('idle');
  const error = ref<{ status: number; message: string } | null>(null);
  const job = ref<{ jobId: string; state: string } | null>(null);
  const plan = ref<PlanRow | null>(null);
  const pricing = ref<PricingView | null>(null);
  /** 授权对话框待确认快照（远程 Need 有报价时填充；取消即清空，零副作用）。 */
  const pendingGrant = ref<{ planId: string; pricingId: string; currency: string; maxAmount: string | null; known: boolean; variantCount: number; needs: string[] } | null>(null);
  /** 取消申请中：已发 cancel、等待服务端终态（SSE 停 ≠ 取消）。 */
  const cancelPending = ref(false);
  const inFlightCancel = ref(false);
  const submitCount = ref(0);

  const busy = computed(() => phase.value !== 'idle' && phase.value !== 'awaiting-grant');

  /**
   * C107F2-37（缺陷 S）：受理切换带意图——只有「分析」受理才切到生成与编辑页签
   * （分析→编辑的既定动线）；regenerate 的 author job 受理不再把用户从方案面板
   * 拽走（自动切换曾让方案页签在重生成期间被反复卸载，generate 按钮不可见）。
   */
  function observe(projectId: string, jobId: string, gate?: RefreshGate, intent?: string): void {
    job.value = { jobId, state: 'running' };
    observers.onJobAccepted?.(jobId, intent);
    observers.watchJob?.(projectId, jobId, (terminal) => {
      job.value = { jobId, state: terminal.state };
      cancelPending.value = false;
      if (phase.value !== 'idle') phase.value = 'idle';
    });
    void gate;
  }

  /** 分析（intent=analyze）：POST agent-jobs 后订阅。 */
  async function analyze(projectId: string, input: { brief: string; assetIds?: string[]; baseRevision?: number; requestId?: string; signal?: AbortSignal }): Promise<void> {
    phase.value = 'analyzing';
    error.value = null;
    try {
      const receipt = await hypitRequest<JobReceipt>(`/projects/${projectId}/agent-jobs`, {
        method: 'POST',
        body: JSON.stringify({
          requestId: input.requestId ?? crypto.randomUUID(),
          intent: 'analyze',
          brief: input.brief,
          assetIds: input.assetIds ?? [],
          baseRevision: input.baseRevision ?? 1,
        }),
        signal: input.signal,
      });
      observe(projectId, receipt.jobId, { signal: input.signal } as RefreshGate, 'analyze');
    } catch (cause) {
      const err = cause as { status?: number; message?: string };
      error.value = { status: err.status ?? 0, message: err.message ?? '分析提交失败' };
      phase.value = 'idle';
      throw cause;
    }
  }

  /** 重新生成方案（intent=author）。 */
  async function regeneratePlan(projectId: string, input: { brief: string; baseRevision: number; requestId?: string; signal?: AbortSignal }): Promise<void> {
    phase.value = 'analyzing';
    error.value = null;
    try {
      const receipt = await hypitRequest<JobReceipt>(`/projects/${projectId}/agent-jobs`, {
        method: 'POST',
        body: JSON.stringify({
          requestId: input.requestId ?? crypto.randomUUID(),
          intent: 'author',
          brief: input.brief,
          assetIds: [],
          baseRevision: input.baseRevision,
        }),
        signal: input.signal,
      });
      observe(projectId, receipt.jobId, { signal: input.signal } as RefreshGate, 'author');
    } catch (cause) {
      const err = cause as { status?: number; message?: string };
      error.value = { status: err.status ?? 0, message: err.message ?? '方案生成提交失败' };
      phase.value = 'idle';
      throw cause;
    }
  }

  /**
   * 生成（check→plan→pricing→授权→submit）。远程 Need → 置 pendingGrant 等待确认并返回
   * 'awaiting-grant'；无远程 Need 直接提交。提交成功 jobId 交观察者（URL+订阅）。
   */
  async function generate(projectId: string, input: { runFile?: string; revision: number; variantCount?: number; signal?: AbortSignal }): Promise<'submitted' | 'awaiting-grant'> {
    const runFile = input.runFile ?? 'main.svrun';
    phase.value = 'planning';
    error.value = null;
    job.value = null;
    try {
      const checked = await hypitRequest<{ ok: boolean; diagnostics: unknown[] }>(`/projects/${projectId}/check`, {
        method: 'POST',
        body: JSON.stringify({ entryFile: 'main.svml' }),
        signal: input.signal,
      });
      if (!checked.ok) throw Object.assign(new Error('源码检查未通过'), { status: 422 });

      const planView = await hypitRequest<{ plan: PlanRow; providers: Array<Record<string, unknown>> }>(`/projects/${projectId}/plan`, {
        method: 'POST',
        body: JSON.stringify({ requestId: stableKey(projectId, runFile, input.revision, 'plan'), runFile }),
        signal: input.signal,
      });
      plan.value = planView.plan;
      phase.value = 'pricing';
      const pricingView = await hypitRequest<PricingView>(`/projects/${projectId}/pricing`, {
        method: 'POST',
        body: JSON.stringify({ requestId: stableKey(projectId, runFile, input.revision, 'pricing'), planId: plan.value.id }),
        signal: input.signal,
      });
      pricing.value = pricingView;
      const remoteNeeds = pricingView.rows.filter((row) => row.need).map((row) => row as unknown as GrantQuoteRow);
      if (remoteNeeds.length > 0) {
        pendingGrant.value = {
          planId: plan.value.id,
          pricingId: pricingView.pricingId,
          currency: remoteNeeds[0].currency ?? 'USD',
          maxAmount: remoteNeeds.every((row) => row.unitAmount !== null)
            ? remoteNeeds.reduce((sum, row) => sum + Number(row.unitAmount), 0).toFixed(2)
            : null,
          known: remoteNeeds.every((row) => row.known),
          variantCount: input.variantCount ?? 1,
          needs: remoteNeeds.map((row) => `${row.need}@${row.provider}${row.unitAmount === null ? '（价格未知）' : ` ${row.unitAmount} ${row.currency}`}`),
        };
        phase.value = 'awaiting-grant';
        return 'awaiting-grant';
      }
      await submit(projectId, runFile, input.revision, input.signal);
      return 'submitted';
    } catch (cause) {
      const err = cause as { status?: number; message?: string };
      error.value = { status: err.status ?? 0, message: err.message ?? '生成失败（输入已保留）' };
      phase.value = 'idle';
      throw cause;
    }
  }

  /** 授权对话框放弃（步骤 2）：清待确认快照，零 grant/build 调用。 */
  function dismissGrant(): void {
    pendingGrant.value = null;
    phase.value = 'idle';
  }

  /** 显式确认授权后一次提交：grant → build submit（稳定 requestId）。 */
  async function confirmGrant(projectId: string, input: { runFile?: string; revision: number; signal?: AbortSignal }): Promise<void> {
    if (!pendingGrant.value) return;
    const runFile = input.runFile ?? 'main.svrun';
    phase.value = 'submitting';
    try {
      const grant = await hypitRequest<{ grantId: string }>(`/projects/${projectId}/execution-grants`, {
        method: 'POST',
        body: JSON.stringify({
          requestId: stableKey(projectId, runFile, input.revision, 'grant'),
          scope: { targets: ['final.video'] },
          maxAmount: pendingGrant.value.maxAmount,
          variantCount: pendingGrant.value.variantCount,
        }),
        signal: input.signal,
      });
      pendingGrant.value = null;
      await submit(projectId, runFile, input.revision, input.signal, grant.grantId);
    } catch (cause) {
      const err = cause as { status?: number; message?: string };
      error.value = { status: err.status ?? 0, message: err.message ?? '授权/提交失败（输入已保留）' };
      phase.value = 'idle';
      throw cause;
    }
  }

  /** 提交构建（TC-04：稳定 requestId——超时重试恢复同一 build/job，不新建重复任务）。 */
  async function submit(projectId: string, runFile: string, revision: number, signal?: AbortSignal, grantId?: string): Promise<void> {
    phase.value = 'submitting';
    submitCount.value += 1;
    const receipt = await hypitRequest<{ build: { id: string }; job: { id: string } }>(`/projects/${projectId}/builds`, {
      method: 'POST',
      body: JSON.stringify({
        requestId: stableKey(projectId, runFile, revision, 'build'),
        planId: plan.value?.id,
        grantId: grantId ?? null,
        title: null,
      }),
      signal,
    });
    phase.value = 'idle';
    // generate 受理也切换页签（intent='generate'）：构建进度在生成与编辑页的素材面板，
    // 不切过去用户点完生成看不到任何反馈（C107F2-37 实录）。
    observe(projectId, receipt.job.id, signal ? ({ signal } as RefreshGate) : undefined, 'generate');
  }

  /**
   * 取消（步骤 4）：POST jobs/{id}/actions cancel（连点只发一个在途动作），置「取消申请中」并
   * 继续观察服务端 cancelled 终态——绝不把 stop SSE 当取消。
   */
  async function cancel(projectId: string, jobId: string, signal?: AbortSignal): Promise<void> {
    if (inFlightCancel.value) return;
    inFlightCancel.value = true;
    cancelPending.value = true;
    try {
      await hypitRequest<{ state: string }>(`/jobs/${jobId}/actions`, {
        method: 'POST',
        body: JSON.stringify({ requestId: crypto.randomUUID(), action: 'cancel' }),
        signal,
      });
    } catch (cause) {
      cancelPending.value = false;
      const err = cause as { status?: number; message?: string };
      error.value = { status: err.status ?? 0, message: err.message ?? '取消失败' };
      throw cause;
    } finally {
      inFlightCancel.value = false;
    }
  }

  return {
    phase, error, job, plan, pricing, pendingGrant, cancelPending, submitCount, busy,
    analyze, regeneratePlan, generate, dismissGrant, confirmGrant, cancel,
  };
}
