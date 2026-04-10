<template>
  <div class="app-shell">
    <header class="page-header">
      <div class="brand">
        <p class="brand-kicker">Douyin Extractor</p>
        <h1 class="brand-title">粘贴抖音链接，直接预览、下载视频并提取音频</h1>
      </div>
      <p class="brand-copy">
        先走匿名链路提取；少数命中校验页的链接，再启用扫码登录增强。提取成功后可直接下载 mp4 或转成 mp3。
      </p>
    </header>

    <main class="layout">
      <section class="input-column">
        <article class="editor-card glass-card">
          <header class="card-head">
            <p class="eyebrow">输入</p>
            <h2 class="card-title">抖音分享文本或链接</h2>
          </header>

          <label class="field-label" for="douyin-input">把抖音 App 复制出来的整段分享文本贴进来</label>
          <textarea
            id="douyin-input"
            v-model="douyinUrl"
            class="input-area"
            rows="7"
            placeholder="例如：7.54 复制打开抖音，看看【...】 https://v.douyin.com/xxxx/"
            :disabled="parseLoading"
          />

          <p class="field-note">
            预览和下载都由后端代理处理。登录增强只保存在后端本地，不会下发到前端。
          </p>

          <div class="action-row">
            <button
              class="btn-primary"
              :disabled="parseLoading || !douyinUrl.trim()"
              @click="handleExtractDouyinVideo"
            >
              {{ parseLoading ? '提取中…' : '提取视频' }}
            </button>
            <button class="btn-secondary" :disabled="parseLoading" @click="handleReset">
              清空
            </button>
          </div>

          <button class="toggle-link" :disabled="parseLoading" @click="showSessionPanel = !showSessionPanel">
            {{ showSessionPanel ? '收起登录增强' : '解析失败时再尝试登录增强' }}
          </button>
        </article>

        <DouyinSessionPanel
          v-if="showSessionPanel"
          :session="douyinSession"
          :loading="sessionLoading"
          :error="sessionError"
          @start="startDouyinSession"
          @refresh="refreshDouyinSession"
          @logout="logoutDouyinSession"
        />
      </section>

      <section class="preview-column">
        <DouyinParsePanel
          :extracted-video="extractedVideo"
          :loading="parseLoading"
          :error="parseError"
          @retry="handleExtractDouyinVideo"
        />

        <section v-if="!parseLoading && !parseError && !extractedVideo" class="empty-card glass-card">
          <p class="empty-kicker">准备就绪</p>
          <h2 class="empty-title">右侧会出现视频预览</h2>
          <p class="empty-copy">
            提取成功后，你可以直接在页面里播放视频，也可以下载 mp4 或提取 mp3 音频附件。
          </p>
        </section>
      </section>
    </main>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import DouyinParsePanel from './components/DouyinParsePanel.vue'
import DouyinSessionPanel from './components/DouyinSessionPanel.vue'
import { useDouyinParse } from './composables/useDouyinParse'
import { useDouyinSession } from './composables/useDouyinSession'

const douyinUrl = ref('')
const showSessionPanel = ref(false)

const {
  extractedVideo,
  loading: parseLoading,
  error: parseError,
  extractVideo,
  reset: resetParse,
} = useDouyinParse()

const {
  state: douyinSession,
  loading: sessionLoading,
  error: sessionError,
  refresh: refreshDouyinSession,
  start: startDouyinSession,
  logout: logoutDouyinSession,
} = useDouyinSession()

onMounted(() => {
  void refreshDouyinSession()
})

async function handleExtractDouyinVideo(): Promise<void> {
  const data = await extractVideo(douyinUrl.value)
  if (!data) {
    showSessionPanel.value = true
  }
}

function handleReset(): void {
  douyinUrl.value = ''
  showSessionPanel.value = false
  resetParse()
}
</script>

<style scoped>
.app-shell {
  width: min(1280px, calc(100% - 32px));
  margin: 0 auto;
  padding: var(--space-page) 0 48px;
}

.page-header {
  display: grid;
  gap: 18px;
  margin-bottom: 28px;
}

.brand {
  display: grid;
  gap: 12px;
}

.brand-kicker,
.eyebrow,
.empty-kicker {
  margin: 0;
  font-size: 0.78rem;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--color-text-muted);
}

.brand-title,
.card-title,
.empty-title {
  margin: 0;
  line-height: 1.05;
}

.brand-title {
  max-width: 12ch;
  font-size: clamp(2.5rem, 1.5rem + 4vw, 5.2rem);
}

.brand-copy,
.field-note,
.empty-copy {
  margin: 0;
  max-width: 60ch;
  color: var(--color-text-secondary);
  font-size: 1rem;
}

.layout {
  display: grid;
  grid-template-columns: 360px minmax(0, 1fr);
  gap: 22px;
  align-items: start;
}

.input-column,
.preview-column {
  display: grid;
  gap: 18px;
}

.input-column {
  position: sticky;
  top: 20px;
}

.editor-card,
.empty-card {
  border-radius: var(--radius-lg);
  padding: 22px;
}

.editor-card {
  display: grid;
  gap: 16px;
}

.card-head {
  display: grid;
  gap: 6px;
}

.card-title,
.empty-title {
  font-size: 1.32rem;
}

.field-label {
  font-size: 0.92rem;
  color: var(--color-text);
  font-weight: 600;
}

.input-area {
  width: 100%;
  resize: vertical;
  min-height: 160px;
  padding: 14px 16px;
  border-radius: 18px;
  border: 1px solid var(--color-border);
  background: rgba(255,255,255,0.05);
  color: var(--color-text);
  outline: none;
  transition: border-color 160ms ease, background 160ms ease;
}

.input-area:focus {
  border-color: rgba(139, 92, 246, 0.45);
  background: rgba(139, 92, 246, 0.06);
}

.input-area:disabled {
  opacity: 0.7;
}

.action-row {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}

.btn-primary,
.btn-secondary,
.toggle-link {
  min-height: 46px;
  padding: 0 18px;
  border-radius: 999px;
  cursor: pointer;
  transition: transform 150ms ease, opacity 150ms ease, background 150ms ease;
}

.btn-primary {
  background: var(--gradient-accent);
  color: white;
  font-weight: 700;
}

.btn-secondary {
  border: 1px solid var(--color-border);
  background: rgba(255,255,255,0.04);
  color: var(--color-text);
}

.toggle-link {
  justify-self: start;
  padding: 0;
  min-height: auto;
  border-radius: 0;
  color: var(--color-text-secondary);
  background: transparent;
  text-decoration: underline;
  text-underline-offset: 4px;
}

.btn-primary:hover:not(:disabled),
.btn-secondary:hover:not(:disabled),
.toggle-link:hover:not(:disabled) {
  transform: translateY(-1px);
}

.btn-primary:disabled,
.btn-secondary:disabled,
.toggle-link:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.empty-card {
  min-height: 320px;
  align-content: end;
  background:
    linear-gradient(180deg, rgba(255,255,255,0.06), rgba(255,255,255,0.02)),
    radial-gradient(circle at top right, rgba(139,92,246,0.18), transparent 35%);
}

.empty-copy {
  margin-top: 8px;
}

@media (max-width: 960px) {
  .layout {
    grid-template-columns: 1fr;
  }

  .input-column {
    position: static;
  }

  .brand-title {
    max-width: 100%;
  }
}
</style>
