<script setup lang="ts">
/**
 * PreviewPanel.vue — C107-21：预览（输入 session/frame；事件 close/retry）。
 * iframe sandbox 仅脚本能力；不 v-html 作者 HTML；加载失败可重试，预览不自动
 * 触发收费生成。
 * C107F2-23（§6.10）：挂载即向 composable 绑定 contentWindow（消息身份第一关）；
 * 会话到期不渲染旧 ticketUrl（空字符串 src 绝不出现），过期给明确「重新打开」。
 */
import { computed, ref, watch } from 'vue';
import type { HypitSessionCreated } from '../../../types/hypit';

const props = defineProps<{
  session: HypitSessionCreated | null;
  loading: boolean;
  error: string | null;
  currentFrame: number | null;
  currentTime: number | null;
  /** C23：iframe 挂载/卸载时回调（绑定/解绑 contentWindow）。 */
  bindFrame?: (source: Window | null) => void;
}>();

const emit = defineEmits<{ close: []; retry: [] }>();

const frameEl = ref<HTMLIFrameElement | null>(null);

/** 会话未到期才暴露 ticketUrl（§6.9 绝对 TTL；缺失按未到期——服务端契约必带，防御缺失）。 */
const previewSrc = computed(() => {
  const session = props.session;
  if (session?.ticketUrl === undefined || session.ticketUrl === null || session.ticketUrl === '') return null;
  if (session.expiresAt !== undefined && Date.parse(session.expiresAt) <= Date.now()) return null;
  return session.ticketUrl;
});

const expired = computed(() =>
  props.session !== null
  && props.session.expiresAt !== undefined
  && Date.parse(props.session.expiresAt) <= Date.now());

watch(frameEl, (el) => {
  props.bindFrame?.(el?.contentWindow ?? null);
});
</script>

<template>
  <section class="gl-zone" data-testid="clone-preview-panel" aria-label="预览">
    <header class="clone-preview-head">
      <h2>预览</h2>
      <button v-if="props.session" type="button" class="gl-btn-secondary" @click="emit('close')">关闭预览</button>
      <button v-else type="button" class="gl-btn-secondary" data-testid="clone-preview-retry" :disabled="props.loading"
        @click="emit('retry')">{{ props.loading ? '打开中…' : '打开预览' }}</button>
    </header>
    <p v-if="props.error" class="clone-error" data-testid="clone-error" role="alert">{{ props.error }}</p>
    <p v-else-if="props.loading" class="clone-loading" aria-live="polite">正在打开预览会话…</p>
    <template v-else-if="previewSrc !== null">
      <iframe ref="frameEl" class="clone-preview-frame" :src="previewSrc" sandbox="allow-scripts"
        :title="`工程预览会话 ${props.session?.sessionId ?? ''}`"></iframe>
      <p v-if="props.currentFrame !== null" class="clone-preview-clock" data-testid="clone-preview-clock" aria-live="off">
        帧 {{ props.currentFrame }} · {{ (props.currentTime ?? 0).toFixed(2) }}s
      </p>
    </template>
    <p v-else-if="expired" class="clone-empty" data-testid="clone-preview-expired">
      预览会话已过期。
      <button type="button" class="gl-btn-secondary" data-testid="clone-preview-reopen" @click="emit('retry')">重新打开</button>
    </p>
    <p v-else class="clone-empty">预览未打开。打开后可逐帧检查当前画面。</p>
  </section>
</template>

<style scoped>
.clone-preview-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.clone-preview-head h2 { margin: 0; font-size: 16px; font-family: var(--font-display); }
.clone-error { color: var(--color-danger); }
.clone-loading, .clone-empty { color: var(--color-text-secondary); }
.clone-preview-frame { width: 100%; aspect-ratio: 9 / 16; max-height: 70vh; border: 1px solid var(--color-border); border-radius: var(--radius-md); background: var(--color-media-canvas); }
.clone-preview-clock { font-size: 12px; color: var(--color-text-secondary); font-variant-numeric: tabular-nums; }
</style>
