<template>
  <section class="ai-control-panel" aria-labelledby="platform-models-title">
    <header class="panel-heading">
      <div>
        <h3 id="platform-models-title">平台模型</h3>
        <p>按能力维护主模型、备用模型、健康状态和并发上限</p>
      </div>
      <button type="button" class="primary-command" data-action="add-model" @click="openCreate">新增配置</button>
    </header>

    <p v-if="error" class="error-state" role="alert">{{ error }}</p>
    <p v-if="loading" class="empty-state">正在加载平台模型...</p>
    <p v-else-if="!error && models.length === 0" class="empty-state">暂无平台模型配置</p>
    <div v-else class="model-table-wrap">
      <table class="model-table">
        <thead><tr><th>能力</th><th>角色</th><th>Provider / 模型</th><th>健康</th><th>并发</th><th>版本</th><th>操作</th></tr></thead>
        <tbody>
          <tr v-for="item in models" :key="item.id">
            <td>{{ capabilityLabel(item.capability) }}</td>
            <td>{{ item.modelRole === 'primary' ? '主模型' : '备用' }}</td>
            <td><strong>{{ item.model }}</strong><small>{{ item.provider }}</small></td>
            <td><span class="health-tag" :class="`health-${item.healthStatus}`">{{ healthLabel(item.healthStatus) }}</span></td>
            <td>{{ item.maxConcurrency == null ? '不限' : item.maxConcurrency }}</td>
            <td>v{{ item.version }}</td>
            <td class="row-actions">
              <button type="button" data-action="edit-model" @click="openEdit(item)">修订</button>
              <button type="button" class="danger-command" data-action="disable-model" @click="disableModel(item)">禁用</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <div v-if="mode" class="form-band">
      <form @submit.prevent="submit">
        <header class="form-heading">
          <h4>{{ mode === 'create' ? '新增平台模型' : `修订配置 · v${target?.version}` }}</h4>
          <button type="button" aria-label="关闭模型表单" @click="closeForm">×</button>
        </header>
        <label>能力<input v-model.trim="capability" name="capability" required maxlength="64" :disabled="mode === 'edit'" /></label>
        <label>模型角色
          <select v-model="modelRole" name="modelRole" :disabled="mode === 'edit'">
            <option value="primary">主模型</option><option value="backup">备用模型</option>
          </select>
        </label>
        <label>Provider<input v-model.trim="provider" name="provider" required maxlength="64" /></label>
        <label>模型<input v-model.trim="model" name="model" required maxlength="128" /></label>
        <label class="wide-field">API Base URL<input v-model.trim="baseUrl" name="baseUrl" type="url" required maxlength="1000" /></label>
        <label>并发上限<input v-model="maxConcurrency" name="maxConcurrency" type="number" min="1" step="1" placeholder="留空表示不限" /></label>
        <label>健康状态
          <select v-model="healthStatus" name="healthStatus">
            <option value="healthy">健康</option><option value="degraded">降级</option><option value="unhealthy">不可用</option>
          </select>
        </label>
        <p v-if="formError" class="error-state compact" role="alert">{{ formError }}</p>
        <div class="form-actions">
          <button type="button" class="secondary-command" @click="closeForm">取消</button>
          <button type="submit" class="primary-command" :disabled="submitting">{{ submitting ? '保存中...' : '保存修订' }}</button>
        </div>
      </form>
    </div>
  </section>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useAiControlPlane } from '../composables/useAiControlPlane'
import type { PlatformModelConfig, PlatformModelHealth, PlatformModelRole } from '../types/ai-control-plane'

const api = useAiControlPlane()
const models = ref<PlatformModelConfig[]>([])
const loading = ref(false)
const error = ref('')
const formError = ref('')
const submitting = ref(false)
const mode = ref<'create' | 'edit' | null>(null)
const target = ref<PlatformModelConfig | null>(null)
const capability = ref('text')
const modelRole = ref<PlatformModelRole>('primary')
const provider = ref('')
const model = ref('')
const baseUrl = ref('')
const maxConcurrency = ref('')
const healthStatus = ref<PlatformModelHealth>('healthy')

onMounted(() => { void loadModels() })

async function loadModels(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    models.value = [...await api.listModels()]
  } catch (caught: unknown) {
    models.value = []
    error.value = caught instanceof Error ? caught.message : '平台模型加载失败'
  } finally {
    loading.value = false
  }
}

function resetForm(): void {
  target.value = null; capability.value = 'text'; modelRole.value = 'primary'; provider.value = ''
  model.value = ''; baseUrl.value = ''; maxConcurrency.value = ''; healthStatus.value = 'healthy'; formError.value = ''
}

function openCreate(): void { resetForm(); mode.value = 'create' }
function openEdit(item: PlatformModelConfig): void {
  resetForm(); mode.value = 'edit'; target.value = item; capability.value = item.capability
  modelRole.value = item.modelRole; provider.value = item.provider; model.value = item.model
  baseUrl.value = item.baseUrl; maxConcurrency.value = item.maxConcurrency?.toString() || ''; healthStatus.value = item.healthStatus
}
function closeForm(): void { resetForm(); mode.value = null }

function concurrencyValue(): number | undefined {
  if (!maxConcurrency.value) return undefined
  const value = Number(maxConcurrency.value)
  return Number.isInteger(value) && value > 0 ? value : undefined
}

async function submit(): Promise<void> {
  const currentMode = mode.value
  const currentTarget = target.value
  if (!currentMode || (currentMode === 'edit' && !currentTarget)) return
  if (maxConcurrency.value && concurrencyValue() == null) {
    formError.value = '并发上限必须是正整数'
    return
  }
  submitting.value = true
  formError.value = ''
  const mutableFields = {
    provider: provider.value, model: model.value, baseUrl: baseUrl.value,
    maxConcurrency: concurrencyValue(), healthStatus: healthStatus.value,
  }
  try {
    if (currentMode === 'create') {
      await api.createModel({ capability: capability.value, modelRole: modelRole.value, ...mutableFields })
    } else if (currentTarget) {
      await api.updateModel(currentTarget.capability, currentTarget.modelRole, mutableFields)
    }
    closeForm()
    await loadModels()
  } catch (caught: unknown) {
    formError.value = caught instanceof Error ? caught.message : '平台模型保存失败'
  } finally {
    submitting.value = false
  }
}

async function disableModel(item: PlatformModelConfig): Promise<void> {
  if (!window.confirm(`确认禁用 ${capabilityLabel(item.capability)}的${item.modelRole === 'primary' ? '主模型' : '备用模型'}？`)) return
  error.value = ''
  try {
    await api.disableModel(item.capability, item.modelRole)
    await loadModels()
  } catch (caught: unknown) {
    error.value = caught instanceof Error ? caught.message : '平台模型禁用失败'
  }
}

function capabilityLabel(value: string): string {
  return { text: '文本', vision: '视觉理解', image_generation: '图片生成', video_understanding: '视频理解',
    video_generation: '视频生成', voice: '语音', content_safety: '内容安全', retrieval: '检索' }[value] || value
}
function healthLabel(value: PlatformModelHealth): string {
  return { healthy: '健康', degraded: '降级', unhealthy: '不可用' }[value]
}
</script>

<style scoped>
.ai-control-panel { display: grid; gap: 16px; }
.panel-heading, .form-heading, .form-actions { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.panel-heading h3, .form-heading h4 { margin: 0; color: var(--color-text); letter-spacing: 0; }
.panel-heading h3 { font-size: 1.05rem; }.form-heading h4 { font-size: .95rem; }
.panel-heading p { margin: 4px 0 0; color: var(--color-text-muted); font-size: .82rem; }
.primary-command, .secondary-command, .row-actions button, .form-heading button { min-height: 34px; padding: 0 12px; border-radius: 6px; cursor: pointer; }
.primary-command { border: 1px solid var(--color-accent); background: var(--color-accent); color: #fff; font-weight: 700; }
.secondary-command, .row-actions button, .form-heading button { border: 1px solid var(--color-border); background: var(--color-surface); color: var(--color-text-secondary); }
.row-actions .danger-command { color: var(--color-danger); }.primary-command:disabled { opacity: .5; cursor: wait; }
.empty-state, .error-state { margin: 0; padding: 22px 0; text-align: center; color: var(--color-text-muted); }
.error-state { color: var(--color-danger); }.error-state.compact { grid-column: 1 / -1; padding: 0; text-align: left; }
.model-table-wrap { overflow-x: auto; border: 1px solid var(--color-border); border-radius: 8px; }
.model-table { width: 100%; min-width: 820px; border-collapse: collapse; font-size: .82rem; }
.model-table th, .model-table td { padding: 11px 12px; text-align: left; border-bottom: 1px solid var(--color-border); }
.model-table tr:last-child td { border-bottom: 0; }.model-table th { color: var(--color-text-muted); background: var(--color-surface-muted); }
.model-table td { color: var(--color-text-secondary); }.model-table strong, .model-table small { display: block; }.model-table strong { color: var(--color-text); }.model-table small { color: var(--color-text-muted); margin-top: 2px; }
.row-actions { white-space: nowrap; }.row-actions button { min-height: 30px; padding: 0 9px; margin-right: 5px; }
.health-tag { display: inline-block; padding: 3px 7px; border-radius: 5px; background: var(--color-surface-muted); }.health-healthy { color: #15803d; }.health-degraded { color: #a16207; }.health-unhealthy { color: var(--color-danger); }
.form-band { padding-top: 16px; border-top: 1px solid var(--color-border); }
form { display: grid; grid-template-columns: 1fr 1fr; gap: 13px; }.form-heading, .form-actions, .wide-field { grid-column: 1 / -1; }
label { display: grid; gap: 6px; color: var(--color-text-secondary); font-size: .82rem; }
input, select { width: 100%; box-sizing: border-box; min-height: 38px; padding: 8px 10px; border: 1px solid var(--color-border); border-radius: 6px; background: var(--color-surface); color: var(--color-text); font: inherit; }
.form-actions { justify-content: flex-end; }
@media (max-width: 700px) { .panel-heading { align-items: flex-start; flex-direction: column; } form { grid-template-columns: 1fr; } form > * { grid-column: 1; } }
</style>
