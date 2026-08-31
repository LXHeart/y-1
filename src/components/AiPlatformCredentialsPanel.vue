<template>
  <section class="ai-control-panel" aria-labelledby="platform-credentials-title">
    <header class="panel-heading">
      <div>
        <h3 id="platform-credentials-title">平台通用凭据</h3>
        <p>provider、服务地址与密钥同行保存；模型配置引用凭据，改密钥不需要发版。页面只显示掩码</p>
      </div>
      <div class="heading-actions">
        <label class="toggle-disabled">
          <input type="checkbox" name="includeDisabledCredentials" :checked="includeDisabled"
                 @change="onToggleDisabled(($event.target as HTMLInputElement).checked)" />
          显示已停用
        </label>
        <button type="button" class="primary-command" data-action="add-credential" @click="openCreate">新增凭据</button>
      </div>
    </header>

    <p v-if="error" class="error-state" role="alert">{{ error }}</p>
    <p v-if="loading" class="empty-state">正在加载平台凭据...</p>
    <p v-else-if="!error && credentials.length === 0" class="empty-state">暂无平台凭据</p>
    <div v-else class="model-table-wrap">
      <table class="model-table">
        <thead><tr><th>标签</th><th>Provider</th><th>服务地址</th><th>密钥</th><th>版本</th><th>操作</th></tr></thead>
        <tbody>
          <tr v-for="item in credentials" :key="item.id" :class="{ 'row-disabled': !item.enabled }">
            <td><strong>{{ item.name }}</strong></td>
            <td>{{ item.provider }}</td>
            <td class="url-cell">{{ item.baseUrl }}</td>
            <td>
              <span v-if="item.hasKey" class="key-tag key-present">{{ item.maskedHint || '已配置' }}</span>
              <span v-else class="key-tag key-absent">
                {{ item.provider === 'sandbox' ? '沙箱免密' : '未配置（走 env 兜底）' }}
              </span>
            </td>
            <td>v{{ item.version }} <small v-if="!item.enabled" class="disabled-tag">已停用</small></td>
            <!-- 停用行只给删除：编辑/轮换/获取模型的后端端点全走 findEnabledById，对停用行本来就 404，
                 UI 与后端口径一致（任务书 #59 D7）。恢复=重新建一行，本任务不做。 -->
            <td class="row-actions">
              <template v-if="item.enabled">
                <button type="button" data-action="edit-credential" @click="openEdit(item)">编辑</button>
                <button type="button" data-action="rotate-credential" @click="openRotate(item)">轮换</button>
                <button type="button" class="danger-command" data-action="disable-credential" @click="disableCredential(item)">停用</button>
                <button type="button" data-action="fetch-models" @click="openPicker(item)">获取模型</button>
              </template>
              <template v-else>
                <button type="button" class="danger-command" data-action="delete-credential"
                        @click="openDeleteConfirm(item)">删除</button>
              </template>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <GlModal v-if="mode" :title="formTitle" @close="closeForm">
      <form id="credential-form" @submit.prevent="submit">
        <template v-if="mode !== 'rotate'">
          <label>标签<input v-model.trim="name" name="name" required maxlength="128" placeholder="如 主力-通义" /></label>
          <label>Provider
            <select v-model="provider" name="provider" required>
              <option value="openai-completions">openai-completions</option>
              <option value="openai-responses">openai-responses</option>
              <option value="anthropic-messages">anthropic-messages</option>
              <option value="google-generative-ai">google-generative-ai</option>
              <option value="openai-compatible">openai-compatible</option>
              <option value="sandbox">sandbox（免密占位）</option>
            </select>
          </label>
          <label class="wide-field">服务地址<input v-model.trim="baseUrl" name="baseUrl" type="url" required maxlength="1000" /></label>
        </template>
        <label v-if="mode !== 'edit'" class="wide-field">
          {{ mode === 'rotate' ? '新密钥' : 'API Key' }}
          <input
            v-model="apiKey"
            name="apiKey"
            type="password"
            autocomplete="new-password"
            maxlength="2048"
            :required="mode === 'rotate'"
            :placeholder="mode === 'create' ? 'sandbox 或先走 env 兜底可留空' : ''"
          />
        </label>
        <p v-if="mode === 'edit'" class="form-hint">改密钥请用「轮换」——编辑只改连接信息，不动密钥</p>
        <p v-if="formError" class="error-state compact" role="alert">{{ formError }}</p>
      </form>
      <template #actions>
        <button type="button" class="secondary-command" @click="closeForm">取消</button>
        <button type="submit" form="credential-form" class="primary-command" :disabled="submitting">{{ submitting ? '保存中...' : '保存' }}</button>
      </template>
    </GlModal>

    <GlModal v-if="pickerTarget" :title="`${pickerTarget.name} · 勾选可用模型`" scroll @close="closePicker">
      <p class="form-hint">
        勾选的模型才会出现在「平台模型」的模型下拉里。上游返回的全部模型见下；
        已勾选但上游本次没返回的仍保留并标注，避免上游抖动时误删线上配置在用的模型。
      </p>
      <p v-if="pickerLoading" class="empty-state">正在获取模型...</p>
      <p v-else-if="pickerError" class="error-state compact" role="alert">{{ pickerError }}</p>
      <p v-else-if="pickerModels.length === 0" class="empty-state">上游未返回任何模型</p>
      <ul v-else class="model-picker">
        <li v-for="item in pickerModels" :key="item.id">
          <label>
            <input type="checkbox" :name="`model-${item.id}`" :checked="ticked.has(item.id)"
                   @change="toggleModel(item.id, ($event.target as HTMLInputElement).checked)" />
            <span class="model-id">{{ item.id }}</span>
            <small v-if="item.ownedBy">{{ item.ownedBy }}</small>
            <small v-if="item.staleSelection" class="stale-tag">上游本次未返回</small>
          </label>
        </li>
      </ul>
      <template #actions>
        <button type="button" class="secondary-command" @click="closePicker">取消</button>
        <button type="button" class="primary-command" data-action="save-models"
                :disabled="pickerSaving || pickerLoading" @click="saveTicked">
          {{ pickerSaving ? '保存中...' : `保存勾选（${ticked.size}）` }}
        </button>
      </template>
    </GlModal>

    <GlModal v-if="deleteTarget" title="删除平台凭据" @close="closeDeleteConfirm">
      <p class="delete-confirm-text">
        确认永久删除凭据「{{ deleteTarget.name }}」（{{ deleteTarget.provider }} · {{ deleteTarget.baseUrl }}）？
        此操作不可恢复，其已勾选的模型白名单将一并删除。
        若仍被模型配置行引用（含已停用历史行），删除会被拒绝。
      </p>
      <p v-if="deleteError" class="error-state compact" role="alert">{{ deleteError }}</p>
      <template #actions>
        <button type="button" class="btn-cancel" @click="closeDeleteConfirm">取消</button>
        <button type="button" class="btn-confirm danger" data-action="confirm-delete-credential"
                :disabled="deleteSubmitting" @click="confirmDelete">
          {{ deleteSubmitting ? '删除中...' : '确认删除' }}
        </button>
      </template>
    </GlModal>
  </section>
</template>
<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useAiControlPlane } from '../composables/useAiControlPlane'
import GlModal from './GlModal.vue'
import type { PlatformProviderCredential, UpstreamModel } from '../types/ai-control-plane'

/** 勾选面板的一行：上游返回的模型，或「已勾选但上游本次没返回」的存量项。 */
interface PickerRow extends UpstreamModel {
  staleSelection?: boolean
}

type FormMode = 'create' | 'edit' | 'rotate' | null

/**
 * 新建表单的 provider 默认值，必须是下拉里真实存在的一项——否则 select 渲染成空白、
 * 提交的是后端正则不认的值（400）。与后端 PlatformProviderNames.OPENAI_COMPLETIONS 对齐。
 */
const DEFAULT_PROVIDER = 'openai-completions'

const api = useAiControlPlane()
const credentials = ref<PlatformProviderCredential[]>([])
const loading = ref(false)
const error = ref('')
const formError = ref('')
const submitting = ref(false)
const mode = ref<FormMode>(null)
const target = ref<PlatformProviderCredential | null>(null)
const name = ref('')
const provider = ref(DEFAULT_PROVIDER)
const baseUrl = ref('')
const apiKey = ref('')

const includeDisabled = ref(false)

const deleteTarget = ref<PlatformProviderCredential | null>(null)
const deleteSubmitting = ref(false)
const deleteError = ref('')

const formTitle = computed(() => {
  if (mode.value === 'create') return '新增平台凭据'
  if (mode.value === 'rotate') return `轮换密钥 · ${target.value?.name ?? ''}`
  return `编辑连接信息 · v${target.value?.version ?? ''}`
})

const pickerTarget = ref<PlatformProviderCredential | null>(null)
const pickerModels = ref<PickerRow[]>([])
const pickerLoading = ref(false)
const pickerSaving = ref(false)
const pickerError = ref('')
const ticked = ref<Set<string>>(new Set())

onMounted(() => { void loadCredentials() })

/**
 * 打开勾选面板：并发拉「上游实时列表」与「已勾选集」，然后合并。
 *
 * 合并而非直接用上游列表：上游抖动或临时不返回某模型时，若只显示上游结果，已勾选项会
 * 悄悄消失，一保存就把线上配置在用的模型从白名单里删掉。故存量勾选项始终保留并标注。
 * 上游整体失败时也不清空已勾选集——那样等于让一次网络故障擦掉运营的选择。
 */
async function openPicker(item: PlatformProviderCredential): Promise<void> {
  closeForm()
  pickerTarget.value = item
  pickerLoading.value = true
  pickerError.value = ''
  pickerModels.value = []
  ticked.value = new Set()
  const [upstream, selected] = await Promise.all([
    api.listCredentialModels(item.id).catch((caught: unknown) => {
      pickerError.value = caught instanceof Error ? caught.message : '上游模型获取失败'
      return [] as UpstreamModel[]
    }),
    api.listSelectedModels(item.id).catch(() => [] as UpstreamModel[]),
  ])
  ticked.value = new Set(selected.map((model) => model.id))
  const upstreamIds = new Set(upstream.map((model) => model.id))
  pickerModels.value = [
    ...upstream,
    ...selected.filter((model) => !upstreamIds.has(model.id))
      .map((model) => ({ ...model, staleSelection: true })),
  ]
  // 上游失败但有存量勾选：清掉报错，面板仍可用（只是列不出新模型）
  if (pickerError.value && pickerModels.value.length > 0) {
    pickerError.value = ''
  }
  pickerLoading.value = false
}

function closePicker(): void {
  pickerTarget.value = null
  pickerModels.value = []
  ticked.value = new Set()
  pickerError.value = ''
  pickerSaving.value = false
}

function toggleModel(id: string, checked: boolean): void {
  const next = new Set(ticked.value)
  if (checked) {
    next.add(id)
  } else {
    next.delete(id)
  }
  ticked.value = next
}

async function saveTicked(): Promise<void> {
  const item = pickerTarget.value
  if (!item) return
  pickerSaving.value = true
  pickerError.value = ''
  try {
    // 整份覆盖：带上 ownedBy 便于「平台模型」下拉展示归属
    const payload = pickerModels.value
      .filter((model) => ticked.value.has(model.id))
      .map((model) => ({ id: model.id, ...(model.ownedBy ? { ownedBy: model.ownedBy } : {}) }))
    await api.replaceSelectedModels(item.id, payload)
    closePicker()
  } catch (caught: unknown) {
    pickerError.value = caught instanceof Error ? caught.message : '勾选保存失败'
  } finally {
    pickerSaving.value = false
  }
}

async function loadCredentials(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    credentials.value = [...await api.listCredentials(includeDisabled.value)]
  } catch (caught: unknown) {
    credentials.value = []
    error.value = caught instanceof Error ? caught.message : '平台凭据加载失败'
  } finally {
    loading.value = false
  }
}

function onToggleDisabled(next: boolean): void {
  includeDisabled.value = next
  void loadCredentials()
}

function openDeleteConfirm(item: PlatformProviderCredential): void {
  deleteTarget.value = item
  deleteError.value = ''
}

function closeDeleteConfirm(): void {
  deleteTarget.value = null
  deleteError.value = ''
  deleteSubmitting.value = false
}

/** 409 引用数原样透出在弹窗内，弹窗不关——运营要看到为什么删不掉。 */
async function confirmDelete(): Promise<void> {
  const item = deleteTarget.value
  if (!item) return
  deleteSubmitting.value = true
  deleteError.value = ''
  try {
    await api.hardDeleteCredential(item.id)
    closeDeleteConfirm()
    await loadCredentials()
  } catch (caught: unknown) {
    deleteError.value = caught instanceof Error ? caught.message : '平台凭据删除失败'
  } finally {
    deleteSubmitting.value = false
  }
}

function resetForm(): void {
  target.value = null; name.value = ''; provider.value = DEFAULT_PROVIDER
  baseUrl.value = ''; apiKey.value = ''; formError.value = ''
}

function openCreate(): void { resetForm(); mode.value = 'create' }
function openEdit(item: PlatformProviderCredential): void {
  resetForm(); mode.value = 'edit'; target.value = item
  name.value = item.name; provider.value = item.provider; baseUrl.value = item.baseUrl
}
function openRotate(item: PlatformProviderCredential): void {
  resetForm(); mode.value = 'rotate'; target.value = item
}
function closeForm(): void { resetForm(); mode.value = null }

async function submit(): Promise<void> {
  const currentMode = mode.value
  const currentTarget = target.value
  if (!currentMode || (currentMode !== 'create' && !currentTarget)) return
  // 明文只在这一瞬间存在于内存，提交前先取出并清空绑定
  const plaintext = apiKey.value
  apiKey.value = ''
  submitting.value = true
  formError.value = ''
  try {
    if (currentMode === 'create') {
      await api.createCredential({
        name: name.value,
        provider: provider.value,
        baseUrl: baseUrl.value,
        apiKey: plaintext || undefined,
      })
    } else if (currentMode === 'edit' && currentTarget) {
      await api.updateCredential(currentTarget.id, {
        name: name.value, provider: provider.value, baseUrl: baseUrl.value,
      })
    } else if (currentTarget) {
      await api.rotateCredentialKey(currentTarget.id, plaintext)
    }
    closeForm()
    await loadCredentials()
  } catch (caught: unknown) {
    formError.value = caught instanceof Error ? caught.message : '平台凭据保存失败'
  } finally {
    submitting.value = false
  }
}

/** 引用中停用会被后端 409 拒绝并报引用数——原样透出，让运营知道要先改指向。 */
async function disableCredential(item: PlatformProviderCredential): Promise<void> {
  if (!window.confirm(`确认停用凭据「${item.name}」？`)) return
  error.value = ''
  try {
    await api.disableCredential(item.id)
    await loadCredentials()
  } catch (caught: unknown) {
    error.value = caught instanceof Error ? caught.message : '平台凭据停用失败'
  }
}
</script>

<style scoped>
.ai-control-panel { display: grid; gap: 16px; }
.panel-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.panel-heading h3 { margin: 0; color: var(--color-text); letter-spacing: 0; font-size: 1.05rem; }
.panel-heading p { margin: 4px 0 0; color: var(--color-text-muted); font-size: .82rem; }
.heading-actions { display: flex; align-items: center; gap: 12px; }
.toggle-disabled { display: flex; align-items: center; gap: 6px; color: var(--color-text-secondary); font-size: .82rem; cursor: pointer; }
.toggle-disabled input { width: auto; min-height: 0; margin: 0; }
.primary-command, .secondary-command, .row-actions button { min-height: 34px; padding: 0 12px; border-radius: var(--radius-sm); cursor: pointer; }
.primary-command { border: 1px solid var(--color-accent); background: var(--color-accent); color: var(--color-on-accent); font-weight: 700; }
.secondary-command, .row-actions button { border: 1px solid var(--color-border); background: var(--color-surface); color: var(--color-text-secondary); }
.row-actions .danger-command { color: var(--color-danger); }.primary-command:disabled { opacity: .5; cursor: wait; }
.empty-state, .error-state { margin: 0; padding: 22px 0; text-align: center; color: var(--color-text-muted); }
.error-state { color: var(--color-danger); }.error-state.compact { grid-column: 1 / -1; padding: 0; text-align: left; }
.model-table-wrap { overflow-x: auto; border: 1px solid var(--color-border); border-radius: var(--radius-md); }
.model-table { width: 100%; min-width: 820px; border-collapse: collapse; font-size: .82rem; }
.model-table th, .model-table td { padding: 11px 12px; text-align: left; border-bottom: 1px solid var(--color-border); }
.model-table tr:last-child td { border-bottom: 0; }.model-table th { color: var(--color-text-muted); background: var(--surface-muted); }
.model-table td { color: var(--color-text-secondary); }.model-table strong { color: var(--color-text); }
.model-table .row-disabled td { opacity: .55; }
.disabled-tag { display: block; margin-top: 2px; color: var(--color-warning); }
.url-cell { overflow-wrap: anywhere; max-width: 280px; }
.row-actions { white-space: nowrap; }.row-actions button { min-height: 30px; padding: 0 9px; margin-right: 5px; }
.key-tag { display: inline-block; padding: 3px 7px; border-radius: var(--radius-sm); background: var(--surface-muted); }
.key-present { color: var(--color-text); font-family: var(--font-mono, ui-monospace), monospace; }
.key-absent { color: var(--color-text-muted); }
.model-picker { display: grid; gap: 6px; margin: 0; padding: 0; list-style: none; }
.model-picker label { display: flex; align-items: center; gap: 8px; cursor: pointer; }
.model-picker input[type="checkbox"] { width: auto; min-height: 0; margin: 0; }
.model-picker .model-id { color: var(--color-text); font-size: .84rem; }
.model-picker small { color: var(--color-text-muted); font-size: .74rem; }
.model-picker .stale-tag { color: var(--color-warning); }
.delete-confirm-text { margin: 0; color: var(--color-text-secondary); font-size: .86rem; line-height: 1.6; }
form { display: grid; grid-template-columns: 1fr 1fr; gap: 13px; }
.wide-field, .form-hint { grid-column: 1 / -1; }
.form-hint { margin: 0; color: var(--color-text-muted); font-size: .78rem; }
label { display: grid; gap: 6px; color: var(--color-text-secondary); font-size: .82rem; }
input, select { width: 100%; box-sizing: border-box; min-height: 38px; padding: 8px 10px; border: 1px solid var(--color-border); border-radius: var(--radius-sm); background: var(--color-surface); color: var(--color-text); font: inherit; }
@media (max-width: 700px) { .panel-heading { align-items: flex-start; flex-direction: column; } form { grid-template-columns: 1fr; } form > * { grid-column: 1; } }
</style>
