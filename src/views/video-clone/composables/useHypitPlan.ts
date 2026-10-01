/**
 * useHypitPlan.ts — C107-21 (task-107) 克隆方案/成本/授权状态（W21 固定职责）：
 * 只读展示与动作触发，不做权威额度判断（服务端 409/422 为准）。
 * C107F2-11：refresh 接受 scope gate + 内部请求 token——旧 success/error/finally
 * 不得改新世代状态；reset 供确认离开时清旧工程数据。
 */
import { onUnmounted, ref } from 'vue';
import { getClonePlan } from './hypit-api';
import type { RefreshGate } from './useHypitProjectScope';
import type { HypitClonePlan } from '../../../types/hypit';

export function useHypitPlan() {
  const plan = ref<HypitClonePlan | null>(null);
  const loading = ref(false);
  const error = ref<{ status: number; message: string } | null>(null);
  const controller = new AbortController();
  let token = 0;

  async function refresh(projectId: string, gate?: RefreshGate): Promise<void> {
    const mine = ++token;
    const signal = gate?.signal ?? controller.signal;
    const ok = () => mine === token && !signal.aborted && (gate?.isCurrent?.() ?? true);
    loading.value = true;
    error.value = null;
    try {
      const next = await getClonePlan(projectId, signal);
      if (!ok()) return;
      plan.value = next;
    } catch (cause) {
      if (!ok()) return;
      const err = cause as { status?: number; message?: string };
      error.value = { status: err.status ?? 0, message: err.message ?? '读取失败' };
      plan.value = null;
    } finally {
      if (ok()) loading.value = false;
    }
  }

  /** 确认离开当前工程时清空本域状态（不 abort 服务端副作用）。 */
  function reset(): void {
    token += 1;
    plan.value = null;
    loading.value = false;
    error.value = null;
  }

  onUnmounted(() => controller.abort());

  return { plan, loading, error, refresh, reset };
}
