<script setup lang="ts">
/**
 * StudioPanel.vue — C107-21：完整 Studio 嵌入面（输入 session；事件
 * close/sessionExpired）。完整编辑器由 Studio 会话本身承载——本面板只负责
 * 会话票据/过期处理，不在 Vue 重写时间线。
 */
import { computed } from 'vue';
import type { HypitSessionCreated } from '../../../types/hypit';

const props = defineProps<{
  session: HypitSessionCreated | null;
  loading: boolean;
  error: string | null;
}>();

const emit = defineEmits<{ close: []; reopen: [] }>();

const studioSrc = computed(() => {
  const url = props.session?.ticketUrl;
  return url === undefined || url === null ? null : url;
});
</script>

<template>
  <section class="gl-zone clone-studio" data-testid="clone-studio-panel" aria-label="专业编辑器">
    <header class="clone-studio-head">
      <h2>专业编辑器</h2>
      <button v-if="studioSrc !== null" type="button" class="gl-btn-secondary" @click="emit('close')">
        关闭编辑器
      </button>
      <button v-else type="button" class="gl-btn-primary" :disabled="props.loading" @click="emit('reopen')">
        {{ props.loading ? '打开中…' : '打开编辑器' }}
      </button>
    </header>
    <p v-if="props.error" class="clone-error" data-testid="clone-error" role="alert">{{ props.error }}</p>
    <p v-else-if="props.loading" class="clone-loading" aria-live="polite">正在打开 Studio 会话…</p>
    <iframe v-else-if="studioSrc !== null" class="clone-studio-frame" :src="studioSrc"
      :title="`Studio 会话 ${props.session?.sessionId ?? ''}`" sandbox="allow-scripts allow-same-origin"></iframe>
    <p v-else class="clone-empty">Studio 未打开。会话票据单次有效，过期后可重新打开。</p>
  </section>
</template>

<style scoped>
.clone-studio-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.clone-studio-head h2 { margin: 0; font-size: 16px; font-family: var(--font-display); }
.clone-error { color: var(--color-danger); }
.clone-loading, .clone-empty { color: var(--color-text-secondary); }
.clone-studio-frame { width: 100%; height: min(72vh, 900px); border: 1px solid var(--color-border); border-radius: var(--radius-md); background: #000; }
</style>
