/**
 * useHypitAssets.ts — C107F2-31（W195）：参考素材取数/上传/交接状态机。
 *
 * 取数与上传经统一 hypit-api 层（不各自 fetch）；工程切换 abort 旧请求并重载
 * （§8.3 ReferencePanel）；上传终态真值始终以服务端列表为准（202 收敛后再
 * 刷新），本 composable 不本地伪造 ready。上传错误保留用户已选文件（§8.3）。
 */
import { onMounted, onUnmounted, ref, watch } from 'vue';

import type { HypitProject } from '../../../types/hypit';
import { importSource, listAssets, uploadAsset, type HypitAssetDto } from './hypit-api';

export interface UseHypitAssetsOptions {
  readonly project: () => HypitProject;
}

export function useHypitAssets(options: UseHypitAssetsOptions) {
  const assets = ref<HypitAssetDto[]>([]);
  const loading = ref(false);
  const error = ref<string | null>(null);
  const handoffNote = ref<string | null>(null);
  const handoffBusy = ref(false);
  const uploading = ref(false);
  const uploadProgress = ref(0);
  const uploadError = ref<string | null>(null);
  /** 上传失败时保留用户已选文件（§8.3：上传错误保留选择）。 */
  const pendingFile = ref<File | null>(null);
  let controller = new AbortController();

  /** 列表拉取本体（不触碰控制器——调用方持自己的 signal）。 */
  async function loadList(signal: AbortSignal): Promise<void> {
    loading.value = true;
    error.value = null;
    try {
      const page = await listAssets(options.project().id, signal);
      if (signal.aborted) return;
      assets.value = page.items;
    } catch (cause) {
      if (signal.aborted) return;
      error.value = (cause as Error).message;
    } finally {
      if (!signal.aborted) loading.value = false;
    }
  }

  async function refresh(): Promise<void> {
    controller.abort();
    controller = new AbortController();
    await loadList(controller.signal);
  }

  /** 创建后进入参考素材：sourceContext 交接物化（幂等），再拉列表。 */
  async function handoffSource(): Promise<void> {
    if (handoffBusy.value) return;
    controller.abort();
    controller = new AbortController();
    const signal = controller.signal;
    handoffBusy.value = true;
    handoffNote.value = null;
    try {
      const outcome = await importSource(options.project().id, signal);
      if (signal.aborted) return;
      handoffNote.value = outcome.note ?? null;
      // 直接以交接自身的 signal 拉列表——经 refresh() 会 abort 本交接的控制器，
      // finally 的 !signal.aborted 判定恒假，handoffBusy 永远停在 true（按钮锁死）。
      await loadList(signal);
    } catch (cause) {
      if (signal.aborted) return;
      handoffNote.value = null;
      error.value = (cause as Error).message;
    } finally {
      if (!signal.aborted) handoffBusy.value = false;
    }
  }

  async function upload(file: File, role = 'reference'): Promise<HypitAssetDto | null> {
    if (uploading.value) return null;
    pendingFile.value = file;
    uploading.value = true;
    uploadProgress.value = 0;
    uploadError.value = null;
    try {
      const asset = await uploadAsset(options.project().id, file, role, crypto.randomUUID(),
        (percent) => { uploadProgress.value = percent; }, controller.signal);
      pendingFile.value = null;
      await refresh();
      return asset;
    } catch (cause) {
      uploadError.value = (cause as Error).message;
      return null;
    } finally {
      uploading.value = false;
      uploadProgress.value = 0;
    }
  }

  function cancelUpload(): void {
    if (!uploading.value) return;
    controller.abort();
    controller = new AbortController();
    uploading.value = false;
    uploadProgress.value = 0;
  }

  function retryPending(): Promise<HypitAssetDto | null> {
    const file = pendingFile.value;
    return file === null ? Promise.resolve(null) : upload(file);
  }

  // C107F2-11（F08）：工作区同实例切工程必须重载并取消旧请求。
  watch(() => options.project().id, () => {
    pendingFile.value = null;
    uploadError.value = null;
    handoffNote.value = null;
    void refresh();
  });

  onMounted(() => { void refresh(); });
  onUnmounted(() => controller.abort());

  return {
    assets, loading, error,
    handoffNote, handoffBusy, handoffSource,
    uploading, uploadProgress, uploadError, pendingFile,
    upload, cancelUpload, retryPending, refresh,
  };
}
