<script setup lang="ts">
/**
 * ReferencePanel.vue — C107-21：参考素材面板（输入 project；事件
 * importUrl/analyze）。展示素材列表与错误态；播放/证据网格由素材内容接口
 * 提供，不在本组件拼 Prompt 调模型。
 */
import { onMounted, onUnmounted, ref } from 'vue';
import { hypitRequest } from '../composables/hypit-api';
import type { HypitProject } from '../../../types/hypit';

const props = defineProps<{ project: HypitProject }>();

const emit = defineEmits<{
  importUrl: [url: string];
  analyze: [assetId: string];
}>();

interface AssetSummary {
  id: string;
  filename: string | null;
  mediaType: string | null;
  sizeBytes: number | null;
  origin: string | null;
}

const assets = ref<AssetSummary[]>([]);
const loading = ref(false);
const error = ref<string | null>(null);
const url = ref('');
const importing = ref(false);
const controller = new AbortController();

async function refresh(): Promise<void> {
  loading.value = true;
  error.value = null;
  try {
    const page = await hypitRequest<{ items: AssetSummary[] }>(`/projects/${props.project.id}/assets?limit=50`, {
      method: 'GET', signal: controller.signal,
    });
    assets.value = page.items;
  } catch (cause) {
    if (controller.signal.aborted) return;
    error.value = (cause as Error).message;
  } finally {
    loading.value = false;
  }
}

async function importFromUrl(): Promise<void> {
  const value = url.value.trim();
  if (!/^https:\/\//u.test(value)) {
    error.value = '仅支持 https 链接导入';
    return;
  }
  importing.value = true;
  error.value = null;
  try {
    await hypitRequest(`/projects/${props.project.id}/import-url`, {
      method: 'POST',
      body: JSON.stringify({ requestId: crypto.randomUUID(), url: value }),
      signal: controller.signal,
    });
    url.value = '';
    await refresh();
  } catch (cause) {
    if (controller.signal.aborted) return;
    error.value = (cause as Error).message;
  } finally {
    importing.value = false;
  }
}

onMounted(refresh);
onUnmounted(() => controller.abort());
</script>

<template>
  <section class="gl-zone" data-testid="clone-reference-panel" aria-label="参考素材">
    <h2>参考素材</h2>
    <form class="clone-url-form" @submit.prevent="importFromUrl">
      <label class="gl-field clone-url-field">
        <span>从链接导入参考视频</span>
        <input v-model="url" type="url" inputmode="url" placeholder="https://…" data-testid="clone-import-url" />
      </label>
      <button type="submit" class="gl-btn-primary" data-testid="clone-import-url-submit" :disabled="importing">
        {{ importing ? '导入中…' : '导入' }}
      </button>
    </form>
    <p v-if="error" class="clone-error" data-testid="clone-error" role="alert">{{ error }}</p>
    <p v-else-if="loading" class="clone-loading" aria-live="polite">正在加载素材…</p>
    <p v-else-if="assets.length === 0" class="clone-empty" data-testid="clone-empty">
      还没有参考素材：上传文件或粘贴支持平台的链接。
    </p>
    <ul v-else class="clone-asset-list">
      <li v-for="asset in assets" :key="asset.id" class="clone-asset">
        <span class="clone-asset-name">{{ asset.filename ?? asset.id }}</span>
        <span class="clone-asset-meta">{{ asset.mediaType ?? '未知类型' }}</span>
        <button type="button" class="gl-btn-secondary" data-testid="clone-analyze" @click="emit('analyze', asset.id)">
          全片分析
        </button>
      </li>
    </ul>
  </section>
</template>

<style scoped>
.clone-url-form { display: flex; align-items: flex-end; gap: 8px; margin: 12px 0; flex-wrap: wrap; }
.clone-url-field { flex: 1; min-width: 220px; }
.clone-error { color: var(--color-danger); }
.clone-loading, .clone-empty { color: var(--color-text-secondary); }
.clone-asset-list { list-style: none; margin: 0; padding: 0; display: grid; gap: 8px; }
.clone-asset { display: flex; align-items: center; gap: 8px; border: 1px solid var(--color-border); border-radius: var(--radius-md); padding: 8px 12px; }
.clone-asset-name { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.clone-asset-meta { font-size: 12px; color: var(--color-text-secondary); }
</style>
