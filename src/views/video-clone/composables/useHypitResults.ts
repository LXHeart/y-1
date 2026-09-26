/**
 * useHypitResults.ts — C107-21 (task-107) 结果/归档/复用动作（W21 固定职责）：
 * download 走服务端归档后的 /api/media/{id}；finish 语义是「补写持久化」
 * 不是重新生成；显式复用走 /reuse（原生 build-record/satisfy 由服务端构造）。
 */
import { onUnmounted, ref } from 'vue';
import {
  archiveOutput,
  listBuilds,
  listOutputs,
} from './hypit-api';
import type { HypitBuild, HypitOutput } from '../../../types/hypit';

export function useHypitResults() {
  const builds = ref<HypitBuild[]>([]);
  const outputs = ref<HypitOutput[]>([]);
  const activeBuildId = ref<string | null>(null);
  const loading = ref(false);
  const error = ref<string | null>(null);
  const archiving = ref<string | null>(null);
  const controller = new AbortController();

  async function refresh(projectId: string): Promise<void> {
    loading.value = true;
    error.value = null;
    try {
      const page = await listBuilds(projectId, controller.signal);
      builds.value = page.items;
      const first = page.items.find((build) => build.resultReady) ?? null;
      if (first !== null) {
        activeBuildId.value = first.id;
        outputs.value = (await listOutputs(projectId, first.id, controller.signal)).items;
      } else {
        activeBuildId.value = null;
        outputs.value = [];
      }
    } catch (cause) {
      if (controller.signal.aborted) return;
      error.value = (cause as Error).message;
    } finally {
      loading.value = false;
    }
  }

  async function selectBuild(projectId: string, buildId: string): Promise<void> {
    activeBuildId.value = buildId;
    outputs.value = [];
    try {
      outputs.value = (await listOutputs(projectId, buildId, controller.signal)).items;
    } catch (cause) {
      if (controller.signal.aborted) return;
      error.value = (cause as Error).message;
    }
  }

  /** 归档单个输出（服务端幂等；重复归档返回既有 mediaId）。 */
  async function archive(projectId: string, buildId: string, outputId: string): Promise<string | null> {
    archiving.value = outputId;
    error.value = null;
    try {
      const receipt = await archiveOutput(projectId, buildId, outputId, {
        requestId: crypto.randomUUID(),
        action: 'archive',
      }, controller.signal);
      await selectBuild(projectId, buildId);
      return receipt.jobId;
    } catch (cause) {
      if (controller.signal.aborted) return null;
      error.value = (cause as Error).message;
      return null;
    } finally {
      archiving.value = null;
    }
  }

  onUnmounted(() => controller.abort());

  return { builds, outputs, activeBuildId, loading, error, archiving, refresh, selectBuild, archive };
}
