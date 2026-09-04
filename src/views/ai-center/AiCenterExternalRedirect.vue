<template>
  <section class="ai-redirect gl-field" aria-live="polite">
    <div class="gl-zone">
      <div class="gl-zone-head">
        <h2 class="gl-zone-title">正在前往 AI 创作中心…</h2>
        <p class="gl-zone-note">AI 内容创作已升级为独立应用；已登录将自动免登进入</p>
      </div>
      <p v-if="redirectError" class="redirect-error" role="alert">
        {{ redirectError }}
      </p>
      <div class="redirect-actions">
        <button type="button" class="gl-btn-primary" :disabled="redirecting" @click="jump">
          {{ redirecting ? '跳转中…' : '立即前往' }}
        </button>
      </div>
    </div>
  </section>
</template>

<script setup lang="ts">
/**
 * 旧链接兼容（任务书 #76 卡 D3）：草场 `/ai-center` 不再是站内页面——
 * 已登录签发跨应用 token 跳 AI 应用（免登），未登录直接跳（AI 应用落游客态/登录页）。
 * `/image-gen → ai-center` 的既有重定向链因此保持不断。
 */
import { onMounted, ref } from 'vue'
import { useCrossAppJump } from '../../composables/useCrossAppToken'

const { jumpToAiApp } = useCrossAppJump()
const redirecting = ref(true)
const redirectError = ref('')

async function jump(): Promise<void> {
  redirecting.value = true
  redirectError.value = ''
  try {
    await jumpToAiApp('/')
  } catch {
    redirectError.value = '跳转失败，请重试或直接访问 AI 创作中心地址。'
    redirecting.value = false
  }
}

onMounted(() => {
  void jump()
})
</script>

<style scoped>
.ai-redirect { display: grid; gap: var(--space-lg); max-width: 640px; margin: 0 auto; }
.redirect-actions { display: flex; gap: var(--space-sm); }
.gl-btn-primary:disabled { opacity: 0.55; cursor: default; }
.redirect-error { margin: 0; color: var(--color-danger); font-size: var(--text-sm); }
</style>
