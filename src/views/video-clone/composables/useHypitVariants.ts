/**
 * useHypitVariants.ts — C107-21 (W21 拆分，§13.3 登记)：变体批次列表/创建/
 * 构建/重试/取消。每项独立 attempt；失败局部重做，批次状态由服务端为准。
 *
 * C107F2-27（F13/F28 前端半边）：取消是真实 POST cancel 而非仅 GET/refresh——
 * 最终状态按服务端回执落项；重试携稳定 requestId（attempt 进键，超时重放同键，
 * 服务端 409 状态闸如实回显不假增）；远程变体构建走 plan→pricing→共享授权对话框
 * （pendingGrant），确认才 execution-grants+build，取消授权零 build 请求；已有
 * grant 被服务端拒（计划不匹配/越界）即失效，重新走授权。
 */
import { onUnmounted, ref } from 'vue';
import {
  buildVariant,
  cancelVariant,
  createVariants,
  hypitRequest,
  listVariants,
  retryVariant,
} from './hypit-api';
import type { RefreshGate } from './useHypitProjectScope';
import type {
  HypitPendingGrant,
  HypitVariantItem,
  HypitVariantMutationResult,
} from '../../../types/hypit';

/** pricing rows 的报价面（与主生成流一致的最小字段）。 */
interface GrantQuoteRow {
  need: string | null;
  provider: string | null;
  currency: string | null;
  unitAmount: string | null;
  known: boolean;
}

export type VariantActResult = 'submitted' | 'awaiting-grant' | 'applied';

/** 稳定幂等键：同 (project, scope, stage) 恒同值；参数变更自然换键（与主生成流同 FNV-1a 方案）。 */
function stableKey(projectId: string, scope: string, stage: string): string {
  const raw = `${projectId}|${scope}|${stage}`;
  let h1 = 0x811c9dc5;
  for (let i = 0; i < raw.length; i += 1) {
    h1 ^= raw.charCodeAt(i);
    h1 = Math.imul(h1, 0x01000193) >>> 0;
  }
  const hex = h1.toString(16).padStart(8, '0').repeat(4);
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-4${hex.slice(13, 16)}-8${hex.slice(17, 20)}-${hex.slice(20, 32)}`;
}

export function useHypitVariants() {
  const items = ref<HypitVariantItem[]>([]);
  const loading = ref(false);
  const error = ref<string | null>(null);
  const actingId = ref<string | null>(null);
  /** 远程构建待确认授权（与主生成流共用 ExecutionGrantDialog 展示）。 */
  const pendingGrant = ref<HypitPendingGrant | null>(null);
  const granting = ref(false);
  const controller = new AbortController();
  let token = 0;
  /** 待授权的目标变体（confirm 时服务端 build 需要它）。 */
  let pendingVariant: HypitVariantItem | null = null;
  /** 本会话已确认的 grant（同工程复用；服务端拒收即失效重新授权）。 */
  let heldGrantId: string | null = null;

  async function refresh(projectId: string, gate?: RefreshGate): Promise<void> {
    const mine = ++token;
    const signal = gate?.signal ?? controller.signal;
    const ok = () => mine === token && !signal.aborted && (gate?.isCurrent?.() ?? true);
    loading.value = true;
    error.value = null;
    try {
      const page = (await listVariants(projectId, signal)).items;
      if (!ok()) return;
      items.value = page;
      scheduleConvergencePoll(projectId);
    } catch (cause) {
      if (!ok()) return;
      error.value = (cause as Error).message;
    } finally {
      if (ok()) loading.value = false;
    }
  }

  /**
   * C107F2-37（缺陷 L 回流 C27）：build/retry 提交后服务端 worker 异步收敛，行状态
   * 只存在于服务端——UI 若不轮询回读，行永远停在提交时快照（草稿/attempt 不动），
   * 真实浏览器链 C37 实录。列表里存在非终态行（running/queued/已提交未收敛的
   * draft）时按固定间隔重读，全部终态或预算耗尽即停；切工程 reset 清表清计时器。
   */
  const POLL_INTERVAL_MS = 3000;
  const POLL_BUDGET_MS = 10 * 60_000;
  let pollTimer: ReturnType<typeof setTimeout> | null = null;
  let pollDeadline = 0;

  function hasInFlightRow(): boolean {
    return items.value.some((item) => item.state === 'running' || item.state === 'queued'
      || (item.state === 'draft' && item.buildId !== undefined));
  }

  function scheduleConvergencePoll(projectId: string): void {
    if (controller.signal.aborted) return;
    if (!hasInFlightRow()) {
      pollDeadline = 0;
      return;
    }
    if (pollDeadline === 0) pollDeadline = Date.now() + POLL_BUDGET_MS;
    else if (Date.now() > pollDeadline) {
      pollDeadline = 0;
      return;
    }
    if (pollTimer !== null) return;
    pollTimer = setTimeout(() => {
      pollTimer = null;
      void refresh(projectId);
    }, POLL_INTERVAL_MS);
  }

  async function createBatch(projectId: string, baseRunFile: string, axes: { key: string; values: string[] }[]): Promise<void> {
    error.value = null;
    try {
      const batch = await createVariants(projectId, {
        requestId: crypto.randomUUID(),
        baseRunFile,
        axes,
      }, controller.signal);
      items.value = batch.items;
    } catch (cause) {
      if (controller.signal.aborted) return;
      error.value = (cause as Error).message;
    }
  }

  /** 服务端回执就地落项（mutation 而非 refresh 推断）；终态以服务端为准。 */
  function applyMutation(result: HypitVariantMutationResult): void {
    items.value = items.value.map((item) => item.id === result.id
      ? { ...item, state: result.state, attempt: result.attempt }
      : item);
  }

  function patchBuildId(variantId: string, buildId: string): void {
    items.value = items.value.map((item) => item.id === variantId
      ? { ...item, buildId }
      : item);
  }

  async function submitBuild(projectId: string, variant: HypitVariantItem, grantId: string | null): Promise<VariantActResult> {
    const receipt = await buildVariant(projectId, variant.id, {
      requestId: stableKey(projectId, `variant|${variant.id}|${variant.attempt}`, 'build'),
      grantId,
    }, controller.signal);
    patchBuildId(variant.id, receipt.buildId);
    return 'submitted';
  }

  /** 报价快照：远程 Need 的 pendingGrant 形态（startBuild 与 grant 失效重授共用）。 */
  function quoteGrant(pricingView: { pricingId: string; planId: string; rows: Array<Record<string, unknown>> }, reauthNote?: string): HypitPendingGrant {
    const remoteNeeds = pricingView.rows.filter((row) => row.need).map((row) => row as unknown as GrantQuoteRow);
    return {
      planId: pricingView.planId,
      pricingId: pricingView.pricingId,
      currency: remoteNeeds[0]?.currency ?? 'USD',
      maxAmount: !reauthNote && remoteNeeds.every((row) => row.unitAmount !== null)
        ? remoteNeeds.reduce((sum, row) => sum + Number(row.unitAmount), 0).toFixed(2)
        : null,
      known: !reauthNote && remoteNeeds.every((row) => row.known),
      variantCount: 1,
      needs: reauthNote ? [reauthNote] : remoteNeeds.map((row) => `${row.need}@${row.provider}${row.unitAmount === null ? '（价格未知）' : ` ${row.unitAmount} ${row.currency}`}`),
      targets: reauthNote ? [] : [...new Set(remoteNeeds.map((row) => row.need as string))],
    };
  }

  /**
   * 构建：先 plan+pricing 报价（与 build 共用同一冻结计划——服务端 build 内
   * planVariant 复用同 runFile 计划），远程 Need 置 pendingGrant 等确认；
   * 本地计划直接提交。已持有 grant 先复用，被服务端拒（计划不匹配/越界）即
   * 失效重新授权。
   */
  async function startBuild(projectId: string, variant: HypitVariantItem): Promise<VariantActResult> {
    const planView = await hypitRequest<{ plan: { id: string }; providers: Array<Record<string, unknown>> }>(
      `/projects/${projectId}/plan`, {
        method: 'POST',
        // fetchApi 对字符串 body 已默认 Content-Type（C107F2-37：显式小写头
        // 会与默认头并存成双值 application/json → Spring 415）。
        body: JSON.stringify({
          requestId: stableKey(projectId, `variant|${variant.id}|${variant.attempt}`, 'plan'),
          runFile: variant.runFile,
        }),
        signal: controller.signal,
      });
    const pricingView = await hypitRequest<{ pricingId: string; planId: string; rows: Array<Record<string, unknown>> }>(
      `/projects/${projectId}/pricing`, {
        method: 'POST',
        body: JSON.stringify({ requestId: crypto.randomUUID(), planId: planView.plan.id }),
        signal: controller.signal,
      });
    const remoteNeeds = pricingView.rows.filter((row) => row.need);
    if (remoteNeeds.length > 0) {
      pendingVariant = variant;
      pendingGrant.value = quoteGrant(pricingView);
      return 'awaiting-grant';
    }
    try {
      return await submitBuild(projectId, variant, heldGrantId);
    } catch (cause) {
      // 已有 grant 不覆盖新计划 → 失效并重走授权（不静默重试越界提交）。
      if (heldGrantId !== null) {
        heldGrantId = null;
        pendingVariant = variant;
        pendingGrant.value = quoteGrant(pricingView, '原授权未覆盖当前计划，需要重新授权');
        return 'awaiting-grant';
      }
      throw cause;
    }
  }

  async function retryItem(projectId: string, variant: HypitVariantItem): Promise<VariantActResult> {
    // 稳定 requestId：attempt 进键——超时重放同键；服务端 409 状态闸重复键不再增。
    const result = await retryVariant(projectId, variant.id, {
      requestId: stableKey(projectId, `variant|${variant.id}|${variant.attempt}`, 'retry'),
    }, controller.signal);
    applyMutation(result);
    return 'applied';
  }

  async function cancelItem(projectId: string, variant: HypitVariantItem): Promise<VariantActResult> {
    // 一次 POST cancel；最终状态按服务端回执，不用 refresh 推断。
    const result = await cancelVariant(projectId, variant.id, {
      requestId: crypto.randomUUID(),
      reason: 'user',
    }, controller.signal);
    applyMutation(result);
    return 'applied';
  }

  async function act(projectId: string, variant: HypitVariantItem, action: 'build' | 'retry' | 'cancel'): Promise<VariantActResult> {
    if (actingId.value !== null) return 'applied';
    actingId.value = variant.id;
    error.value = null;
    try {
      const result = action === 'build' ? await startBuild(projectId, variant)
        : action === 'retry' ? await retryItem(projectId, variant)
        : await cancelItem(projectId, variant);
      // 提交面动作后立即开收敛轮询（本地补写的 buildId/回执态需要服务端终态回收）。
      scheduleConvergencePoll(projectId);
      return result;
    } catch (cause) {
      if (controller.signal.aborted) return 'applied';
      const err = cause as { status?: number; message?: string };
      error.value = err.message ?? '操作失败（输入已保留）';
      return 'applied';
    } finally {
      actingId.value = null;
    }
  }

  /** 确认授权：execution-grants（scope=定价 need 集合，max=单项×项数）→ build。 */
  async function confirmGrant(projectId: string): Promise<void> {
    if (!pendingGrant.value || !pendingVariant) return;
    const variant = pendingVariant;
    granting.value = true;
    error.value = null;
    try {
      const single = pendingGrant.value.maxAmount;
      const grant = await hypitRequest<{ grantId: string }>(`/projects/${projectId}/execution-grants`, {
        method: 'POST',
        body: JSON.stringify({
          requestId: stableKey(projectId, `variant|${variant.id}|${variant.attempt}`, 'grant'),
          scope: { targets: pendingGrant.value.targets },
          maxAmount: single === null ? null
            : (Number(single) * pendingGrant.value.variantCount).toFixed(2),
          variantCount: pendingGrant.value.variantCount,
        }),
        signal: controller.signal,
      });
      heldGrantId = grant.grantId;
      pendingGrant.value = null;
      pendingVariant = null;
      await submitBuild(projectId, variant, grant.grantId);
    } catch (cause) {
      if (controller.signal.aborted) return;
      const err = cause as { status?: number; message?: string };
      error.value = err.message ?? '授权/构建失败（变体已保留）';
    } finally {
      granting.value = false;
    }
  }

  /** 取消授权对话框：清待确认快照——零 grant/build 请求，变体保留原状态。 */
  function dismissGrant(): void {
    pendingGrant.value = null;
    pendingVariant = null;
  }

  /** 确认离开当前工程时清空本域状态（不 abort 服务端副作用）；收敛轮询一并停。 */
  function reset(): void {
    token += 1;
    items.value = [];
    loading.value = false;
    error.value = null;
    pendingGrant.value = null;
    pendingVariant = null;
    heldGrantId = null;
    if (pollTimer !== null) {
      clearTimeout(pollTimer);
      pollTimer = null;
    }
    pollDeadline = 0;
  }

  onUnmounted(() => controller.abort());

  return {
    items, loading, error, actingId, pendingGrant, granting,
    refresh, createBatch, act, confirmGrant, dismissGrant, reset,
  };
}
