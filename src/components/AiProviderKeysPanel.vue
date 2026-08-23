<template>
  <section class="ai-control-panel" aria-labelledby="ai-provider-keys-title">
    <header class="panel-heading">
      <div>
        <h3 id="ai-provider-keys-title">个人模型密钥</h3>
        <p>个人密钥优先于组织密钥；组织密钥由管理员在工作台-组织管理区维护。密钥加密保存，页面只显示掩码</p>
      </div>
      <button type="button" class="primary-command" data-action="add-key" @click="openCreate">添加密钥</button>
    </header>

    <p v-if="error" class="error-state" role="alert">{{ error }}</p>
    <p v-if="loading" class="empty-state">正在加载密钥...</p>
    <p v-else-if="!error && personalKeys.length === 0" class="empty-state">暂无个人模型密钥</p>
    <div v-else class="key-list">
      <article v-for="key in personalKeys" :key="key.id" class="key-row">
        <div class="key-main">
          <strong>{{ capabilityLabel(key.capability) }} · {{ key.model || '默认模型' }}</strong>
          <span>{{ key.provider }} · {{ key.maskedHint }}</span>
          <small>{{ key.baseUrl }}</small>
        </div>
        <div class="row-actions">
          <button type="button" data-action="edit-key" @click="openEdit(key)">编辑</button>
          <button type="button" data-action="rotate-key" @click="openRotate(key)">轮换</button>
          <button type="button" class="danger-command" data-action="disable-key" @click="disableKey(key)">停用</button>
        </div>
      </article>
    </div>

    <div v-if="mode" class="form-band">
      <form @submit.prevent="submit">
        <header class="form-heading">
          <h4>{{ mode === 'create' ? '添加个人密钥' : mode === 'edit' ? '编辑连接配置' : '轮换密钥' }}</h4>
          <button type="button" aria-label="关闭密钥表单" @click="closeForm">×</button>
        </header>
        <template v-if="mode !== 'rotate'">
          <label>能力
            <select v-model="capability" name="capability" :disabled="mode === 'edit'" required>
              <option value="text">文本</option><option value="image">图片理解</option>
              <option value="image_generation">图片生成</option><option value="video_generation">视频生成</option>
            </select>
          </label>
          <label>Provider<input v-model.trim="provider" name="provider" required maxlength="64" :disabled="mode === 'edit'" /></label>
          <label class="wide-field">API Base URL<input v-model.trim="baseUrl" name="baseUrl" type="url" required maxlength="1000" /></label>
          <label>模型<input v-model.trim="model" name="model" maxlength="128" /></label>
        </template>
        <label v-if="mode !== 'edit'" class="wide-field">{{ mode === 'rotate' ? '新密钥' : 'API Key' }}
          <input v-model="apiKey" name="apiKey" type="password" required autocomplete="new-password" maxlength="2048" />
        </label>
        <p v-if="formError" class="error-state compact" role="alert">{{ formError }}</p>
        <div class="form-actions">
          <button type="button" class="secondary-command" @click="closeForm">取消</button>
          <button type="submit" class="primary-command" :disabled="submitting">{{ submitting ? '保存中...' : '保存' }}</button>
        </div>
      </form>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { AiControlPlaneError, useAiControlPlane } from '../composables/useAiControlPlane'
import type { AiProviderCapability, AiProviderKey } from '../types/ai-control-plane'

type FormMode = 'create' | 'edit' | 'rotate' | null

const api = useAiControlPlane()
const keys = ref<AiProviderKey[]>([])
const loading = ref(false)
const error = ref('')
const formError = ref('')
const submitting = ref(false)
const mode = ref<FormMode>(null)
const target = ref<AiProviderKey | null>(null)
const capability = ref<AiProviderCapability>('text')
const provider = ref('openai-compatible')
const baseUrl = ref('')
const model = ref('')
const apiKey = ref('')
const personalKeys = computed(() => keys.value.filter((key) => key.organizationId === null && key.enabled))

onMounted(() => { void loadKeys() })

async function loadKeys(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    keys.value = [...await api.listKeys()]
  } catch (caught: unknown) {
    keys.value = []
    const message = caught instanceof Error ? caught.message : '密钥加载失败'
    error.value = caught instanceof AiControlPlaneError && caught.status === 404
      ? '当前环境未启用个人密钥托管'
      : message
  } finally {
    loading.value = false
  }
}

function resetForm(): void {
  target.value = null
  capability.value = 'text'
  provider.value = 'openai-compatible'
  baseUrl.value = ''
  model.value = ''
  apiKey.value = ''
  formError.value = ''
}

function openCreate(): void { resetForm(); mode.value = 'create' }
function openEdit(key: AiProviderKey): void {
  resetForm(); target.value = key; mode.value = 'edit'; capability.value = key.capability
  provider.value = key.provider; baseUrl.value = key.baseUrl; model.value = key.model || ''
}
function openRotate(key: AiProviderKey): void { resetForm(); target.value = key; mode.value = 'rotate' }
function closeForm(): void { resetForm(); mode.value = null }

async function submit(): Promise<void> {
  const currentMode = mode.value
  const currentTarget = target.value
  if (!currentMode || (currentMode !== 'create' && !currentTarget)) return
  const plaintext = apiKey.value
  apiKey.value = ''
  submitting.value = true
  formError.value = ''
  try {
    if (currentMode === 'create') {
      await api.createKey({ capability: capability.value, provider: provider.value, baseUrl: baseUrl.value,
        model: model.value || undefined, apiKey: plaintext })
    } else if (currentMode === 'edit' && currentTarget) {
      await api.updateKey(currentTarget.id, { baseUrl: baseUrl.value, model: model.value || undefined })
    } else if (currentTarget) {
      await api.rotateKey(currentTarget.id, plaintext)
    }
    closeForm()
    await loadKeys()
  } catch (caught: unknown) {
    formError.value = caught instanceof Error ? caught.message : '密钥保存失败'
  } finally {
    submitting.value = false
  }
}

async function disableKey(key: AiProviderKey): Promise<void> {
  if (!window.confirm(`确认停用 ${capabilityLabel(key.capability)} 的个人密钥？`)) return
  error.value = ''
  try {
    await api.disableKey(key.id)
    await loadKeys()
  } catch (caught: unknown) {
    error.value = caught instanceof Error ? caught.message : '密钥停用失败'
  }
}

function capabilityLabel(value: AiProviderCapability): string {
  return { text: '文本', image: '图片理解', image_generation: '图片生成', video_generation: '视频生成' }[value]
}
</script>

<style scoped>
.ai-control-panel { display: grid; gap: 16px; }
.panel-heading, .form-heading, .key-row, .row-actions, .form-actions { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.panel-heading h3, .form-heading h4 { margin: 0; color: var(--color-text); letter-spacing: 0; }
.panel-heading h3 { font-size: 1.05rem; }.form-heading h4 { font-size: 0.95rem; }
.panel-heading p { margin: 4px 0 0; color: var(--color-text-muted); font-size: 0.82rem; }
.primary-command, .secondary-command, .row-actions button, .form-heading button { min-height: 34px; padding: 0 12px; border-radius: 6px; cursor: pointer; }
.primary-command { border: 1px solid var(--color-accent); background: var(--color-accent); color: var(--color-on-accent); font-weight: 700; }
.secondary-command, .row-actions button, .form-heading button { border: 1px solid var(--color-border); background: var(--color-surface); color: var(--color-text-secondary); }
.row-actions .danger-command { color: var(--color-danger); }
.primary-command:disabled { opacity: .5; cursor: wait; }
.empty-state, .error-state { margin: 0; padding: 22px 0; text-align: center; color: var(--color-text-muted); }
.error-state { color: var(--color-danger); }.error-state.compact { grid-column: 1 / -1; padding: 0; text-align: left; }
.key-list { display: grid; border: 1px solid var(--color-border); border-radius: 8px; overflow: hidden; }
.key-row { padding: 13px 14px; border-bottom: 1px solid var(--color-border); }.key-row:last-child { border-bottom: 0; }
.key-main { display: grid; gap: 3px; min-width: 0; }.key-main strong { color: var(--color-text); }.key-main span, .key-main small { color: var(--color-text-muted); overflow-wrap: anywhere; }
.form-band { padding-top: 16px; border-top: 1px solid var(--color-border); }
form { display: grid; grid-template-columns: 1fr 1fr; gap: 13px; }
.form-heading, .form-actions, .wide-field { grid-column: 1 / -1; }
label { display: grid; gap: 6px; color: var(--color-text-secondary); font-size: .82rem; }
input, select { width: 100%; box-sizing: border-box; min-height: 38px; padding: 8px 10px; border: 1px solid var(--color-border); border-radius: 6px; background: var(--color-surface); color: var(--color-text); font: inherit; }
.form-actions { justify-content: flex-end; }
@media (max-width: 700px) { .panel-heading, .key-row { align-items: flex-start; flex-direction: column; }.row-actions { width: 100%; }.row-actions button { flex: 1; } form { grid-template-columns: 1fr; } form > * { grid-column: 1; } }
</style>
