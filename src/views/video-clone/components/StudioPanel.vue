<script setup lang="ts">
/**
 * StudioPanel.vue — C107-21：完整 Studio 嵌入面（输入 session；事件
 * close/sessionExpired）。完整编辑器由 Studio 会话本身承载——本面板只负责
 * 会话票据/过期处理，不在 Vue 重写时间线。
 * C107F2-20：会话到期（expiresAt 绝对 TTL）后不再渲染旧 ticketUrl——
 * 空态给「重新打开」，绝不把失效 URL 带进 iframe。
 */
import { computed } from 'vue';
import type { HypitSessionCreated } from '../../../types/hypit';

const props = defineProps<{
  session: HypitSessionCreated | null;
  loading: boolean;
  error: string | null;
}>();

const emit = defineEmits<{ close: []; reopen: [] }>();

/** 会话未到期才暴露 ticketUrl（expiresAt 缺失按未到期——服务端契约必带，防御缺失）。 */
const studioSrc = computed(() => {
  const session = props.session;
  if (session?.ticketUrl === undefined || session.ticketUrl === null) return null;
  if (session.expiresAt !== undefined && Date.parse(session.expiresAt) <= Date.now()) return null;
  return session.ticketUrl;
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
    <p v-else class="clone-empty" data-testid="clone-studio-empty">
      {{ props.session !== null ? '会话已过期，请重新打开。' : 'Studio 未打开。会话票据单次有效，过期后可重新打开。' }}
    </p>
  </section>
</template>

<style scoped>
.clone-studio-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.clone-studio-head h2 { margin: 0; font-size: 16px; font-family: var(--font-display); }
.clone-error { color: var(--color-danger); }
.clone-loading, .clone-empty { color: var(--color-text-secondary); }
.clone-studio-frame { width: 100%; height: min(72vh, 900px); border: 1px solid var(--color-border); border-radius: var(--radius-md); background: var(--color-media-canvas); }
</style>
