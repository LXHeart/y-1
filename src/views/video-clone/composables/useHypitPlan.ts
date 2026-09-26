/**
 * useHypitPlan.ts — C107-21 (task-107) 克隆方案/成本/授权状态（W21 固定职责）：
 * 只读展示与动作触发，不做权威额度判断（服务端 409/422 为准）。
 */
import { onUnmounted, ref } from 'vue';
import { getClonePlan } from './hypit-api';
import type { HypitClonePlan } from '../../../types/hypit';

export function useHypitPlan() {
  const plan = ref<HypitClonePlan | null>(null);
  const loading = ref(false);
  const error = ref<{ status: number; message: string } | null>(null);
  const controller = new AbortController();

  async function refresh(projectId: string): Promise<void> {
    loading.value = true;
    error.value = null;
    try {
      plan.value = await getClonePlan(projectId, controller.signal);
    } catch (cause) {
      if (controller.signal.aborted) return;
      const err = cause as { status?: number; message?: string };
      error.value = { status: err.status ?? 0, message: err.message ?? '读取失败' };
      plan.value = null;
    } finally {
      loading.value = false;
    }
  }

  onUnmounted(() => controller.abort());

  return { plan, loading, error, refresh };
}
