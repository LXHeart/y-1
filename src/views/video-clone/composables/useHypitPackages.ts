/**
 * useHypitPackages.ts — C107F2-30 (W187 NEW)：工程包导出→浏览器下载与
 * zip 上传→导入新工程。导出：202 AcceptedJob 后轮询 exportStatus（稳定
 * requestId 重放安全），download 元数据就绪即触发真实浏览器 download 事件
 * （owner 会话 Cookie 直连站内下载路径，不经 artifactRoot）。导入：选包→
 * XHR 真实字节进度上传→202 后轮询新工程状态 provisioning→ready 导航，
 * failed 如实展示（无 ready 假工程）；断开/超时按同 requestId 重查。
 */
import { onUnmounted, ref } from 'vue';
import { exportProject, exportStatus, getProject, importPackage } from './hypit-api';
import type { HypitPackageDownload } from '../../../types/hypit';

export type PackageExportPhase = 'idle' | 'preparing' | 'ready' | 'failed';
export type PackageImportPhase = 'idle' | 'selected' | 'uploading' | 'provisioning' | 'ready' | 'failed';

const POLL_INTERVAL_MS = 1200;
const POLL_MAX_TRIES = 100;

export function useHypitPackages() {
  const exportPhase = ref<PackageExportPhase>('idle');
  const download = ref<HypitPackageDownload | null>(null);
  const exportError = ref<string | null>(null);

  const importPhase = ref<PackageImportPhase>('idle');
  const importProgress = ref(0);
  const importError = ref<string | null>(null);
  const importedProjectId = ref<string | null>(null);
  /** 同 requestId 重查（断开/超时后恢复同一导入，不产生第二工程）。 */
  const importRequestId = ref<string | null>(null);

  let selectedFile: File | null = null;
  let exportToken = 0;
  let importToken = 0;
  let pollTimer: ReturnType<typeof setTimeout> | null = null;
  const controller = new AbortController();

  function clearPoll(): void {
    if (pollTimer !== null) {
      clearTimeout(pollTimer);
      pollTimer = null;
    }
  }

  /** 导出：202 后轮询状态；完成后 download 元数据可触发浏览器下载。 */
  async function startExport(projectId: string, title?: string): Promise<void> {
    const mine = ++exportToken;
    clearPoll();
    exportPhase.value = 'preparing';
    download.value = null;
    exportError.value = null;
    try {
      const receipt = await exportProject(projectId, {
        requestId: crypto.randomUUID(),
        ...(title === undefined ? {} : { title }),
      }, controller.signal);
      if (mine !== exportToken) return;
      let tries = 0;
      const poll = async (): Promise<void> => {
        if (mine !== exportToken) return;
        tries += 1;
        try {
          const status = await exportStatus(receipt.exportId, controller.signal);
          if (mine !== exportToken) return;
          if (status.download !== undefined && status.download !== null) {
            download.value = status.download;
            exportPhase.value = 'ready';
            return;
          }
          if (status.status === 'failed') {
            exportPhase.value = 'failed';
            exportError.value = '导出失败（服务端任务未完成）';
            return;
          }
        } catch (cause) {
          if (controller.signal.aborted || mine !== exportToken) return;
          exportPhase.value = 'failed';
          exportError.value = (cause as Error).message;
          return;
        }
        if (tries >= POLL_MAX_TRIES) {
          exportPhase.value = 'failed';
          exportError.value = '导出超时——请稍后按同一入口重试';
          return;
        }
        pollTimer = setTimeout(() => { void poll(); }, POLL_INTERVAL_MS);
      };
      await poll();
    } catch (cause) {
      if (controller.signal.aborted || mine !== exportToken) return;
      exportPhase.value = 'failed';
      exportError.value = (cause as Error).message ?? '导出提交失败';
    }
  }

  /** 真实浏览器 download 事件：站内路径 + owner 会话 Cookie（不暴露 artifactRoot）。 */
  function triggerBrowserDownload(): void {
    if (download.value === null) return;
    const anchor = document.createElement('a');
    anchor.href = download.value.downloadPath;
    anchor.download = download.value.filename;
    anchor.rel = 'noopener';
    document.body.appendChild(anchor);
    anchor.click();
    anchor.remove();
  }

  function selectFile(file: File): void {
    selectedFile = file;
    importPhase.value = file === null ? 'idle' : 'selected';
    importProgress.value = 0;
    importError.value = null;
    importedProjectId.value = null;
  }

  /** 导入：XHR 真实进度上传 → 轮询新工程 ready → 交导航回调。 */
  async function startImport(onImported: (projectId: string) => void): Promise<void> {
    if (selectedFile === null) return;
    const mine = ++importToken;
    const requestId = importRequestId.value ?? crypto.randomUUID();
    importRequestId.value = requestId;
    importPhase.value = 'uploading';
    importProgress.value = 0;
    importError.value = null;
    try {
      const receipt = await importPackage(selectedFile, requestId, undefined, (percent) => {
        if (mine === importToken) importProgress.value = percent;
      }, controller.signal);
      if (mine !== importToken) return;
      importPhase.value = 'provisioning';
      let tries = 0;
      const poll = async (): Promise<void> => {
        if (mine !== importToken) return;
        tries += 1;
        try {
          const project = await getProject(receipt.projectId, controller.signal);
          if (mine !== importToken) return;
          if (project.status === 'ready') {
            importedProjectId.value = project.id;
            importPhase.value = 'ready';
            onImported(project.id);
            return;
          }
          if (project.status === 'provisioning_failed' || project.status === 'deleted') {
            importPhase.value = 'failed';
            importError.value = '导入失败：包未通过校验（无可用工程产生）';
            return;
          }
        } catch (cause) {
          if (controller.signal.aborted || mine !== importToken) return;
          // 查询失败可重试（断开按同 requestId 查询），不立刻判死。
          if (tries >= POLL_MAX_TRIES) {
            importPhase.value = 'failed';
            importError.value = (cause as Error).message;
            return;
          }
        }
        if (tries >= POLL_MAX_TRIES) {
          importPhase.value = 'failed';
          importError.value = '导入状态查询超时——请按同一文件重试（同 requestId 幂等）';
          return;
        }
        pollTimer = setTimeout(() => { void poll(); }, POLL_INTERVAL_MS);
      };
      await poll();
    } catch (cause) {
      if (controller.signal.aborted || mine !== importToken) return;
      importPhase.value = 'failed';
      importError.value = (cause as Error).message ?? '导入失败（工程未创建）';
    }
  }

  /** 关闭弹窗/切换工程时清本地域（服务端任务不受影响）。 */
  function reset(): void {
    exportToken += 1;
    importToken += 1;
    clearPoll();
    exportPhase.value = 'idle';
    download.value = null;
    exportError.value = null;
    importPhase.value = 'idle';
    importProgress.value = 0;
    importError.value = null;
    importedProjectId.value = null;
    selectedFile = null;
  }

  onUnmounted(() => {
    controller.abort();
    clearPoll();
  });

  return {
    exportPhase, download, exportError,
    importPhase, importProgress, importError, importedProjectId,
    startExport, triggerBrowserDownload, selectFile, startImport, reset,
  };
}
