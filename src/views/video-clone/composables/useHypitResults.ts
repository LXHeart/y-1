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
  readMediaDownloadUrl,
} from './hypit-api';
import type { RefreshGate } from './useHypitProjectScope';
import type { HypitBuild, HypitOutput } from '../../../types/hypit';

export function useHypitResults() {
  const builds = ref<HypitBuild[]>([]);
  const outputs = ref<HypitOutput[]>([]);
  const activeBuildId = ref<string | null>(null);
  const loading = ref(false);
  const error = ref<string | null>(null);
  const archiving = ref<string | null>(null);
  const controller = new AbortController();
  let token = 0;

  async function refresh(projectId: string, gate?: RefreshGate): Promise<void> {
    const mine = ++token;
    const signal = gate?.signal ?? controller.signal;
    const ok = () => mine === token && !signal.aborted && (gate?.isCurrent?.() ?? true);
    loading.value = true;
    error.value = null;
    try {
      const page = await listBuilds(projectId, signal);
      if (!ok()) return;
      builds.value = page.items;
      // C107F2-37（缺陷 Y）：选中条件是「首个 finished build」而不是 resultReady——
      // resultReady=已归档（result_location 落位），拿它做预选会让首次归档死锁：
      // 未归档→不选中→outputs 不拉→归档按钮不存在。outputs 端点自身会做结果
      // 发现同步（finished 未归档也列出待归档产物）。
      const first = page.items.find((build) => build.lifecycle === 'finished') ?? null;
      if (first !== null) {
        const items = (await listOutputs(projectId, first.id, signal)).items;
        if (!ok()) return;
        activeBuildId.value = first.id;
        outputs.value = items;
        // C107F3-11（W77）：装载路径与 selectBuild 同权——重访历史工程时已归档
        // 行的短时签名链接只能在此生成；此前 refresh 不 enrich，重访页的下载
        // 按钮永远停在「下载签名中…」（TC-F3-11-02 重查腿实录，0 次签名请求）。
        void enrichDownloadUrls(first.id);
      } else {
        activeBuildId.value = null;
        outputs.value = [];
      }
    } catch (cause) {
      if (!ok()) return;
      error.value = (cause as Error).message;
    } finally {
      if (ok()) loading.value = false;
    }
  }

  async function selectBuild(projectId: string, buildId: string): Promise<void> {
    activeBuildId.value = buildId;
    outputs.value = [];
    try {
      outputs.value = (await listOutputs(projectId, buildId, controller.signal)).items;
      void enrichDownloadUrls(buildId);
    } catch (cause) {
      if (controller.signal.aborted) return;
      error.value = (cause as Error).message;
    }
  }

  /**
   * C107F2-37（缺陷 AB）：/api/media/{id} 是元数据端点不是字节流，下载链接必须
   * 用它签发的短时 downloadUrl。失败 fail-soft（链接不出现，列表与归档态照常）。
   */
  async function enrichDownloadUrls(buildId: string): Promise<void> {
    const targets = outputs.value.filter(
      (output) => output.archiveState === 'archived' && output.mediaId !== null && !output.downloadUrl,
    );
    await Promise.all(targets.map(async (output) => {
      try {
        const url = await readMediaDownloadUrl(output.mediaId as string, controller.signal);
        if (activeBuildId.value !== buildId) return;
        const current = outputs.value.find((row) => row.id === output.id);
        if (current && url !== null) current.downloadUrl = url;
      } catch {
        // fail-soft：单项签名失败不影响其余输出，用户可重选 Build 重试。
      }
    }));
  }

  /**
   * C107F2-12（F12 修复）：按名归档（POST /builds/{id}/archive，outputNames 载荷）。
   * 成功后重读权威列表保持归档状态；失败保留输出列表供单项重试。
   */
  async function archive(projectId: string, buildId: string, output: HypitOutput): Promise<boolean> {
    archiving.value = output.id;
    error.value = null;
    try {
      await archiveOutput(buildId, [output.name], controller.signal);
      if (controller.signal.aborted) return false;
      await selectBuild(projectId, buildId);
      return true;
    } catch (cause) {
      if (controller.signal.aborted) return false;
      error.value = (cause as Error).message;
      return false;
    } finally {
      archiving.value = null;
    }
  }

  /** 确认离开当前工程时清空本域状态（不 abort 服务端副作用）。 */
  function reset(): void {
    token += 1;
    builds.value = [];
    outputs.value = [];
    activeBuildId.value = null;
    loading.value = false;
    error.value = null;
  }

  onUnmounted(() => controller.abort());

  return { builds, outputs, activeBuildId, loading, error, archiving, refresh, selectBuild, archive, reset };
}
