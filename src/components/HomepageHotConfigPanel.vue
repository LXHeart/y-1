<template>
  <section class="ai-control-panel" aria-labelledby="homepage-hot-title">
    <header class="panel-heading">
      <div>
        <h3 id="homepage-hot-title">首页热点数据源</h3>
        <p>平台级配置：所有访问者（含匿名）的热点来源统一由此决定；无配置时默认 60s</p>
      </div>
    </header>

    <p v-if="error" class="error-state" role="alert">{{ error }}</p>
    <p v-if="loading" class="empty-state">正在加载配置...</p>

    <div v-else class="config-band">
      <div class="provider-switch" role="radiogroup" aria-label="热点数据源">
        <button type="button" role="radio" :aria-checked="provider === '60s'"
          :class="{ active: provider === '60s' }" data-action="use-60s"
          @click="provider = '60s'">60s（默认）</button>
        <button type="button" role="radio" :aria-checked="provider === 'alapi'"
          :class="{ active: provider === 'alapi' }" data-action="use-alapi"
          @click="provider = 'alapi'">ALAPI</button>
      </div>
      <p class="meta-line" v-if="version > 0">
        当前版本 v{{ version }}<template v-if="updatedBy"> · 由 {{ updatedByShort() }} 更新</template><template v-if="updatedAt"> · {{ formatTime(updatedAt) }}</template>
      </p>
      <p class="meta-line" v-else>尚未配置——当前生效的是内置默认（60s）</p>

      <div v-if="provider === 'alapi'" class="token-field">
        <label for="alapi-token-input">ALAPI Token</label>
        <p v-if="tokenMasked" class="token-hint">
          已配置 {{ tokenMasked }}——留空保持不变；输入空格后保存可清空
        </p>
        <p v-else class="token-hint">尚未配置 Token，切换到 ALAPI 前需填写</p>
        <input id="alapi-token-input" v-model="alapiToken" name="alapiToken" type="password"
          autocomplete="new-password" spellcheck="false" placeholder="留空则保持现有 Token">
      </div>

      <p v-if="saveError" class="error-state compact" role="alert">{{ saveError }}</p>
      <div class="form-actions">
        <button type="button" class="primary-command" data-action="save-config"
          :disabled="saving" @click="save">
          {{ saving ? '保存中...' : '保存配置' }}
        </button>
      </div>
    </div>
  </section>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { request } from '../composables/grassland-http'

interface HotConfig {
  provider: string
  alapiTokenMasked?: string | null
  hasAlapiToken: boolean
  version: number
  updatedBy?: string | null
  updatedAt?: string | null
}

const provider = ref<'60s' | 'alapi'>('60s')
const alapiToken = ref('')
const tokenMasked = ref('')
const version = ref(0)
const updatedBy = ref('')
const updatedAt = ref('')
const loading = ref(false)
const error = ref('')
const saving = ref(false)
const saveError = ref('')

onMounted(() => { void load() })

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const config = await request<HotConfig>('/api/admin/homepage/hot-config')
    provider.value = config.provider === 'alapi' ? 'alapi' : '60s'
    tokenMasked.value = config.alapiTokenMasked || ''
    version.value = config.version
    updatedBy.value = config.updatedBy || ''
    updatedAt.value = config.updatedAt || ''
    alapiToken.value = ''
  } catch (err: unknown) {
    error.value = err instanceof Error ? err.message : '加载配置失败'
  } finally {
    loading.value = false
  }
}

async function save(): Promise<void> {
  saving.value = true
  saveError.value = ''
  try {
    const raw = alapiToken.value
    const config = await request<HotConfig>('/api/admin/homepage/hot-config', {
      method: 'PUT',
      body: JSON.stringify({
        provider: provider.value,
        // 不输入 = 保持；空格 = 清空（trim 后为空且原值非空串）；其余 = 新值
        alapiToken: raw === '' ? undefined : raw,
        expectedVersion: version.value,
      }),
    })
    tokenMasked.value = config.alapiTokenMasked || ''
    version.value = config.version
    updatedBy.value = config.updatedBy || ''
    updatedAt.value = config.updatedAt || ''
    alapiToken.value = ''
  } catch (err: unknown) {
    saveError.value = err instanceof Error ? err.message : '保存配置失败'
  } finally {
    saving.value = false
  }
}

function updatedByShort(): string {
  return updatedBy.value ? `${updatedBy.value.slice(0, 8)}…` : ''
}

function formatTime(value: string): string {
  return value ? new Date(value).toLocaleString() : ''
}
</script>

<style scoped>
.ai-control-panel { display: grid; gap: 16px; }
.panel-heading, .form-heading, .form-actions { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.panel-heading h3, .form-heading h4 { margin: 0; color: var(--color-text); letter-spacing: 0; }
.panel-heading h3 { font-size: 1.05rem; }
.panel-heading p { margin: 4px 0 0; color: var(--color-text-muted); font-size: .82rem; }
.primary-command { min-height: 34px; padding: 0 12px; border-radius: var(--radius-sm); cursor: pointer; border: 1px solid var(--color-accent); background: var(--color-accent); color: var(--color-on-accent); font-weight: 700; }
.primary-command:disabled { opacity: .5; cursor: wait; }
.empty-state, .error-state { margin: 0; padding: 22px 0; text-align: center; color: var(--color-text-muted); }
.error-state { color: var(--color-danger); }
.error-state.compact { padding: 0; text-align: left; }
.config-band { display: grid; gap: 14px; justify-items: start; }
.provider-switch { display: inline-flex; gap: 4px; padding: 4px; border-radius: var(--radius-md); border: 1px solid var(--color-border); background: var(--surface-page); }
.provider-switch button { min-height: 32px; padding: 0 14px; border: none; border-radius: var(--radius-xs); background: transparent; color: var(--color-text-secondary); font: inherit; font-size: .84rem; font-weight: 600; cursor: pointer; }
.provider-switch button.active { background: var(--surface-card); border: 1px solid var(--color-border); color: var(--color-text); }
.meta-line { margin: 0; color: var(--color-text-muted); font-size: .8rem; }
.token-field { display: grid; gap: 6px; width: min(420px, 100%); }
.token-field label { color: var(--color-text-secondary); font-size: .82rem; font-weight: 600; }
.token-hint { margin: 0; color: var(--color-text-muted); font-size: .78rem; }
.token-field input { width: 100%; min-height: 38px; padding: 8px 12px; border-radius: var(--radius-sm); border: 1px solid var(--color-border); background: var(--surface-muted); color: var(--color-text); font: inherit; }
.form-actions { width: 100%; justify-content: flex-end; }
</style>
