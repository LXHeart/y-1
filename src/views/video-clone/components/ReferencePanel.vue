<script setup lang="ts">
/**
 * ReferencePanel.vue — C107F2-31：参考素材面板（输入 project；事件
 * analyze）。三种入口与实际能力一致（§8.3）：文件上传（file input，
 * 256MiB 内真实进度）、链接导入（既有 SSRF/平台限制）、交接提示
 * （sourceContext 物化说明）。素材列表/错误态来自 useHypitAssets；
 * 播放/证据网格由素材内容接口提供，不在本组件拼 Prompt 调模型。
 */
import { computed, ref } from 'vue';

import type { HypitProject } from '../../../types/hypit';
import { useHypitAssets } from '../composables/useHypitAssets';

const props = defineProps<{ project: HypitProject }>();

const emit = defineEmits<{
  importUrl: [url: string];
  analyze: [assetId: string];
}>();

const MAX_UPLOAD_BYTES = 256 * 1024 * 1024;

const {
  assets, loading, error,
  handoffNote, handoffBusy,
  uploading, uploadProgress, uploadError, pendingFile,
  upload, cancelUpload, retryPending,
} = useHypitAssets({ project: () => props.project });

const fileInput = ref<HTMLInputElement | null>(null);
const pickedName = ref<string | null>(null);

const pickedTooLarge = computed(() =>
  pickedName.value !== null
  && fileInput.value !== null
  && (fileInput.value.files?.[0]?.size ?? 0) > MAX_UPLOAD_BYTES);

function onPicked(): void {
  const file = fileInput.value?.files?.[0] ?? null;
  pickedName.value = file?.name ?? null;
}

async function submitUpload(): Promise<void> {
  const file = fileInput.value?.files?.[0] ?? null;
  if (file === null) return;
  const asset = await upload(file);
  if (asset !== null) {
    pickedName.value = null;
    if (fileInput.value) fileInput.value.value = '';
  }
}

const url = ref('');
const importing = ref(false);
const localError = ref<string | null>(null);

async function importFromUrl(): Promise<void> {
  const value = url.value.trim();
  if (!/^https:\/\//u.test(value)) {
    localError.value = '仅支持 https 链接导入';
    return;
  }
  importing.value = true;
  localError.value = null;
  try {
    emit('importUrl', value);
    url.value = '';
  } finally {
    importing.value = false;
  }
}
</script>

<template>
  <section class="gl-zone" data-testid="clone-reference-panel" aria-label="参考素材">
    <h2>参考素材</h2>

    <p v-if="handoffBusy" class="clone-loading" aria-live="polite">正在交接参考素材…</p>
    <p v-else-if="handoffNote" class="clone-note" data-testid="clone-handoff-note">{{ handoffNote }}</p>

    <form class="clone-upload-form" @submit.prevent="submitUpload">
      <label class="gl-field clone-upload-field">
        <span>上传参考视频（≤256MiB，服务端校验真实格式）</span>
        <input ref="fileInput" type="file" accept="video/*,audio/*,image/*"
          data-testid="clone-upload-input" :disabled="uploading" @change="onPicked" />
      </label>
      <button type="submit" class="gl-btn-primary" data-testid="clone-upload-submit"
        :disabled="uploading || pickedName === null || pickedTooLarge">
        {{ uploading ? `上传中 ${uploadProgress}%` : '上传素材' }}
      </button>
      <button v-if="uploading" type="button" class="gl-btn-secondary" data-testid="clone-upload-cancel"
        @click="cancelUpload">取消</button>
    </form>
    <p v-if="pickedTooLarge" class="clone-error" data-testid="clone-upload-too-large" role="alert">
      文件超过 256MiB 上限，请压缩后重试。
    </p>
    <p v-else-if="uploading" class="clone-progress" aria-live="polite">
      <progress :value="uploadProgress" max="100" data-testid="clone-upload-progress"></progress>
    </p>
    <p v-else-if="uploadError" class="clone-error" data-testid="clone-upload-error" role="alert">
      {{ uploadError }}
      <button v-if="pendingFile" type="button" class="gl-btn-secondary" data-testid="clone-upload-retry"
        @click="retryPending">重试</button>
    </p>

    <form class="clone-url-form" @submit.prevent="importFromUrl">
      <label class="gl-field clone-url-field">
        <span>从链接导入参考视频（支持平台与既有抓取限制一致）</span>
        <input v-model="url" type="url" inputmode="url" placeholder="https://…" data-testid="clone-import-url" />
      </label>
      <button type="submit" class="gl-btn-primary" data-testid="clone-import-url-submit" :disabled="importing">
        {{ importing ? '导入中…' : '导入' }}
      </button>
    </form>

    <p v-if="error ?? localError" class="clone-error" data-testid="clone-error" role="alert">{{ error ?? localError }}</p>
    <p v-else-if="loading" class="clone-loading" aria-live="polite">正在加载素材…</p>
    <p v-else-if="assets.length === 0" class="clone-empty" data-testid="clone-empty">
      还没有参考素材：上传文件或粘贴支持平台的链接。
    </p>
    <ul v-else class="clone-asset-list">
      <li v-for="asset in assets" :key="asset.id" class="clone-asset">
        <span class="clone-asset-name">{{ asset.mediaId ?? asset.resourceHandle }}</span>
        <span class="clone-asset-meta">{{ asset.mimeType }} · {{ asset.originKind }}</span>
        <button type="button" class="gl-btn-secondary" data-testid="clone-analyze" @click="emit('analyze', asset.id)">
          全片分析
        </button>
      </li>
    </ul>
  </section>
</template>

<style scoped>
.clone-upload-form, .clone-url-form { display: flex; align-items: flex-end; gap: 8px; margin: 12px 0; flex-wrap: wrap; }
.clone-upload-field, .clone-url-field { flex: 1; min-width: 220px; }
.clone-progress progress { width: 100%; height: 8px; }
.clone-error { color: var(--color-danger); }
.clone-note { color: var(--color-text-secondary); }
.clone-loading, .clone-empty { color: var(--color-text-secondary); }
.clone-asset-list { list-style: none; margin: 0; padding: 0; display: grid; gap: 8px; }
.clone-asset { display: flex; align-items: center; gap: 8px; border: 1px solid var(--color-border); border-radius: var(--radius-md); padding: 8px 12px; }
.clone-asset-name { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.clone-asset-meta { font-size: 12px; color: var(--color-text-secondary); }
</style>
