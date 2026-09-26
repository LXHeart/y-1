/**
 * useHypitVariants.ts — C107-21 (W21 拆分，§13.3 登记)：变体批次列表/创建/
 * 构建/重试/取消。每项独立 attempt；失败局部重做，批次状态由服务端为准。
 */
import { onUnmounted, ref } from 'vue';
import {
  buildVariant,
  createVariants,
  listVariants,
  retryVariant,
} from './hypit-api';
import type { HypitVariantItem } from '../../../types/hypit';

export function useHypitVariants() {
  const items = ref<HypitVariantItem[]>([]);
  const loading = ref(false);
  const error = ref<string | null>(null);
  const actingId = ref<string | null>(null);
  const controller = new AbortController();

  async function refresh(projectId: string): Promise<void> {
    loading.value = true;
    error.value = null;
    try {
      items.value = (await listVariants(projectId, controller.signal)).items;
    } catch (cause) {
      if (controller.signal.aborted) return;
      error.value = (cause as Error).message;
    } finally {
      loading.value = false;
    }
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

  async function act(projectId: string, variant: HypitVariantItem, action: 'build' | 'retry' | 'cancel'): Promise<void> {
    actingId.value = variant.id;
    error.value = null;
    try {
      if (action === 'build') {
        await buildVariant(projectId, variant.id, { requestId: crypto.randomUUID(), grantId: null }, controller.signal);
      } else if (action === 'retry') {
        await retryVariant(projectId, variant.id, controller.signal);
      }
      await refresh(projectId);
    } catch (cause) {
      if (controller.signal.aborted) return;
      error.value = (cause as Error).message;
    } finally {
      actingId.value = null;
    }
  }

  onUnmounted(() => controller.abort());

  return { items, loading, error, actingId, refresh, createBatch, act };
}
