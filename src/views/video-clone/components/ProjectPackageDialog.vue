<script setup lang="ts">
/**
 * ProjectPackageDialog.vue — C107F2-30 (W189 NEW，§8.2/§8.3)：工程包导出/上传
 * 弹窗。导出：准备中→可下载（真实浏览器 download 事件）→失败可重试；上传：
 * 选包→真实字节进度→provisioning→ready 导航，失败说明无工程产生。所有状态
 * 如实展示（不虚构 ready），零请求在本组件（动作语义在 useHypitPackages）。
 */
import GlModal from '../../../components/GlModal.vue';
import type { HypitPackageDownload } from '../../../types/hypit';
import type { PackageExportPhase, PackageImportPhase } from '../composables/useHypitPackages';

const props = defineProps<{
  open: boolean;
  projectTitle: string;
  exportPhase: PackageExportPhase;
  download: HypitPackageDownload | null;
  exportError: string | null;
  importPhase: PackageImportPhase;
  importProgress: number;
  importError: string | null;
  importedProjectId: string | null;
}>();

const emit = defineEmits<{
  close: [];
  'start-export': [];
  'trigger-download': [];
  'file-selected': [file: File];
  'start-import': [];
  navigate: [projectId: string];
}>();

function onFileChange(event: Event): void {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  if (file !== undefined) emit('file-selected', file);
  // 允许同一文件重复选择触发 change。
  input.value = '';
}

function onNavigate(): void {
  if (props.importedProjectId !== null) emit('navigate', props.importedProjectId);
}
</script>

<template>
  <GlModal v-if="props.open" title="工程包" persistent @close="emit('close')">
    <div class="package-body" data-testid="clone-package-dialog">
      <section class="package-section">
        <h3>导出</h3>
        <p class="package-hint">打包当前工程（源码 / 素材 / 包依赖）为可移植 ZIP，24 小时内可重复下载。</p>
        <p v-if="props.exportPhase === 'idle'" class="package-hint">尚未开始导出。</p>
        <p v-else-if="props.exportPhase === 'preparing'" class="package-state" aria-live="polite"
          data-testid="clone-package-export-preparing">正在打包…</p>
        <template v-else-if="props.exportPhase === 'ready' && props.download !== null">
          <p class="package-state package-state--ok" data-testid="clone-package-export-ready">
            可下载：{{ props.download.filename }}（{{ Math.max(1, Math.round(props.download.sizeBytes / 1024)) }} KB）
          </p>
          <button type="button" class="gl-btn-primary" data-testid="clone-package-download"
            @click="emit('trigger-download')">下载 ZIP</button>
        </template>
        <template v-else-if="props.exportPhase === 'failed'">
          <p class="package-state package-state--error" data-testid="clone-package-export-error" role="alert">
            {{ props.exportError ?? '导出失败' }}
          </p>
          <button type="button" class="gl-btn-secondary" @click="emit('start-export')">重试导出</button>
        </template>
        <button v-if="props.exportPhase === 'idle' || props.exportPhase === 'ready'"
          type="button" class="gl-btn-secondary" data-testid="clone-package-export"
          @click="emit('start-export')">{{ props.exportPhase === 'ready' ? '重新导出' : '开始导出' }}</button>
      </section>

      <section class="package-section">
        <h3>导入</h3>
        <p class="package-hint">上传工程包 ZIP 将创建一个新工程（owner 为你），原包权限不继承。</p>
        <label class="gl-field">
          <span>选择 ZIP 文件</span>
          <input type="file" accept=".zip" data-testid="clone-package-file" :disabled="props.importPhase === 'uploading'
            || props.importPhase === 'provisioning'" @change="onFileChange" />
        </label>
        <p v-if="props.importPhase === 'selected'" class="package-hint" data-testid="clone-package-file-selected">
          已选包，可开始上传。
        </p>
        <template v-if="props.importPhase === 'uploading' || props.importPhase === 'provisioning'">
          <div class="package-progress" role="progressbar" :aria-valuenow="props.importProgress" aria-valuemin="0"
            aria-valuemax="100" data-testid="clone-package-progress">
            <div class="package-progress-bar" :style="{ width: `${props.importPhase === 'provisioning' ? 100 : props.importProgress}%` }" />
          </div>
          <p class="package-state" aria-live="polite">
            {{ props.importPhase === 'uploading' ? `上传中 ${props.importProgress}%` : '服务端解包校验中…' }}
          </p>
        </template>
        <p v-if="props.importPhase === 'ready' && props.importedProjectId !== null"
          class="package-state package-state--ok" data-testid="clone-package-import-ready">
          新工程已就绪。
        </p>
        <p v-if="props.importPhase === 'failed'" class="package-state package-state--error"
          data-testid="clone-package-import-error" role="alert">{{ props.importError ?? '导入失败' }}</p>
        <button v-if="props.importPhase === 'selected'" type="button" class="gl-btn-primary"
          data-testid="clone-package-import" @click="emit('start-import')">开始导入</button>
        <button v-if="props.importPhase === 'ready' && props.importedProjectId !== null" type="button"
          class="gl-btn-primary" data-testid="clone-package-navigate" @click="onNavigate">打开新工程</button>
      </section>
    </div>
    <footer class="package-actions">
      <button type="button" class="gl-btn-secondary" data-testid="clone-package-close" @click="emit('close')">
        关闭
      </button>
    </footer>
  </GlModal>
</template>

<style scoped>
.package-body { display: grid; gap: 16px; }
.package-section { display: grid; gap: 8px; }
.package-section h3 { margin: 0; font-size: 14px; }
.package-hint { margin: 0; color: var(--color-text-secondary); font-size: 13px; }
.package-state { margin: 0; color: var(--color-text); }
.package-state--ok { color: var(--color-success); }
.package-state--error { color: var(--color-danger); }
.package-progress { height: 6px; border-radius: var(--radius-pill); background: var(--surface-muted); overflow: hidden; }
.package-progress-bar { height: 100%; background: var(--color-primary); transition: width 120ms ease; }
.package-actions { display: flex; justify-content: flex-end; margin-top: 16px; }
</style>
