<template>
  <section class="ai-control-panel" aria-labelledby="price-tables-title">
    <header class="panel-heading">
      <div>
        <h3 id="price-tables-title">价目表</h3>
        <p>平台向用户计费的单价（分/计量单位）；存量 Run 按其冻结的版本结算</p>
      </div>
      <button type="button" class="primary-command" data-action="copy-active"
              :disabled="!activeVersion" @click="openCopyForm">
        复制当前版本调价
      </button>
    </header>

    <p v-if="error" class="error-state" role="alert">{{ error }}</p>
    <p v-if="loading" class="empty-state">正在加载价目表...</p>
    <p v-else-if="!error && versions.length === 0" class="empty-state">暂无价目表版本</p>
    <div v-else class="version-table-wrap">
      <table class="version-table">
        <thead><tr><th>版本</th><th>状态</th><th>说明</th><th>生效时间</th><th>操作</th></tr></thead>
        <tbody>
          <tr v-for="item in versions" :key="item.id" :class="{ 'row-active': item.status === 'active' }">
            <td><strong>{{ item.label }}</strong></td>
            <td><span class="status-tag" :class="`status-${item.status}`">{{ statusLabel(item.status) }}</span></td>
            <td class="note-cell">{{ item.note || '—' }}</td>
            <td>{{ item.activatedAt ? formatTime(item.activatedAt) : '—' }}</td>
            <td class="row-actions">
              <button type="button" data-action="view-models" @click="openModels(item)">单价</button>
              <button v-if="item.status === 'draft'" type="button" data-action="activate-version"
                      @click="activateVersion(item)">激活</button>
              <button v-if="item.status === 'draft'" type="button" class="danger-command"
                      data-action="delete-draft" @click="deleteDraft(item)">删除</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <GlModal v-if="copyFormOpen" :title="`复制 ${activeVersion?.label} 为新 draft`" @close="closeCopyForm">
      <form id="price-copy-form" @submit.prevent="submitCopy">
        <p class="form-hint">
          生效中的单价不可直接改——存量 Run 按其冻结版本结算，改了等于篡改历史账。
          正确路径是复制成 draft、改完再激活。
        </p>
        <label>新版本号<input v-model.trim="newLabel" name="label" required maxlength="64" placeholder="如 v3" /></label>
        <label>说明<input v-model.trim="newNote" name="note" maxlength="500" placeholder="本次调价原因" /></label>
        <p v-if="copyError" class="error-state compact" role="alert">{{ copyError }}</p>
      </form>
      <template #actions>
        <button type="button" class="secondary-command" @click="closeCopyForm">取消</button>
        <button type="submit" form="price-copy-form" class="primary-command" :disabled="copySubmitting">
          {{ copySubmitting ? '创建中...' : '创建 draft' }}
        </button>
      </template>
    </GlModal>

    <GlModal v-if="modelsTarget" wide scroll :persistent="editable"
             :title="`${modelsTarget.label} · 逐模型单价${editable ? '（可改）' : '（已冻结）'}`"
             @close="closeModels">
      <p v-if="!editable" class="form-hint">
        {{ modelsTarget.status === 'active' ? '生效中' : '已退役' }}的版本单价只读。
        要调价请用「复制当前版本调价」。
      </p>
      <p v-if="modelsError" class="error-state compact" role="alert">{{ modelsError }}</p>
      <p v-if="modelsLoading" class="empty-state">正在加载单价...</p>
      <div v-else class="price-table-wrap">
        <table class="price-table">
          <thead>
            <tr>
              <th>模型</th><th>能力</th><th>Provider</th>
              <th>输入<small>分/1k token</small></th>
              <th>输出<small>分/1k token</small></th>
              <th>图片<small>分/张</small></th>
              <th>视频<small>分/秒</small></th>
              <th v-if="editable"></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(row, index) in editRows" :key="`${row.modelId}-${index}`">
              <td>
                <input v-model.trim="row.modelId" :name="`modelId-${index}`" :readonly="!editable"
                       :disabled="!editable" maxlength="128" />
              </td>
              <td>
                <input v-model.trim="row.capability" :name="`capability-${index}`" :readonly="!editable"
                       :disabled="!editable" maxlength="64" />
              </td>
              <td>
                <input v-model.trim="row.provider" :name="`provider-${index}`" :readonly="!editable"
                       :disabled="!editable" maxlength="64" />
              </td>
              <td>
                <input v-model="row.centsPer1kInputTokens" :name="`input-${index}`" type="number" min="0"
                       :readonly="!editable" :disabled="!editable" />
              </td>
              <td>
                <input v-model="row.centsPer1kOutputTokens" :name="`output-${index}`" type="number" min="0"
                       :readonly="!editable" :disabled="!editable" />
              </td>
              <td>
                <input v-model="row.centsPerImage" :name="`image-${index}`" type="number" min="0"
                       :readonly="!editable" :disabled="!editable" />
              </td>
              <td>
                <input v-model="row.centsPerSecond" :name="`second-${index}`" type="number" min="0"
                       :readonly="!editable" :disabled="!editable" />
              </td>
              <td v-if="editable">
                <button type="button" class="danger-command" :data-action="`remove-row-${index}`"
                        @click="removeRow(index)">移除</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <template v-if="editable" #actions>
        <button type="button" class="secondary-command" data-action="add-row" @click="addRow">新增模型</button>
        <button type="button" class="primary-command" data-action="save-models"
                :disabled="modelsSaving" @click="saveModels">
          {{ modelsSaving ? '保存中...' : '保存单价' }}
        </button>
      </template>
    </GlModal>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useAiControlPlane } from '../composables/useAiControlPlane'
import GlModal from './GlModal.vue'
import type { PriceModelEntry, PriceTableStatus, PriceTableVersion } from '../types/ai-control-plane'

const api = useAiControlPlane()
const versions = ref<PriceTableVersion[]>([])
const loading = ref(false)
const error = ref('')

const copyFormOpen = ref(false)
const newLabel = ref('')
const newNote = ref('')
const copyError = ref('')
const copySubmitting = ref(false)

const modelsTarget = ref<PriceTableVersion | null>(null)
const editRows = ref<PriceModelEntry[]>([])
const modelsLoading = ref(false)
const modelsSaving = ref(false)
const modelsError = ref('')

const activeVersion = computed(() => versions.value.find((item) => item.status === 'active') || null)
/** 只有 draft 可改——后端同样拦（409），这里只是不给出误导性的可编辑外观。 */
const editable = computed(() => modelsTarget.value?.status === 'draft')

onMounted(() => { void loadVersions() })

async function loadVersions(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    versions.value = [...await api.listPriceTables()]
  } catch (caught: unknown) {
    versions.value = []
    error.value = caught instanceof Error ? caught.message : '价目表加载失败'
  } finally {
    loading.value = false
  }
}

function openCopyForm(): void {
  closeModels()
  newLabel.value = ''
  newNote.value = ''
  copyError.value = ''
  copyFormOpen.value = true
}

function closeCopyForm(): void {
  copyFormOpen.value = false
  copyError.value = ''
}

async function submitCopy(): Promise<void> {
  const source = activeVersion.value
  if (!source || !newLabel.value) return
  copySubmitting.value = true
  copyError.value = ''
  try {
    const draft = await api.createPriceTableDraft({
      label: newLabel.value,
      note: newNote.value || undefined,
      copyFromVersionId: source.id,
    })
    closeCopyForm()
    await loadVersions()
    // 直接打开新 draft 的单价面板：复制完紧接着就是要改价
    await openModels(draft)
  } catch (caught: unknown) {
    copyError.value = caught instanceof Error ? caught.message : '创建 draft 失败'
  } finally {
    copySubmitting.value = false
  }
}

async function openModels(item: PriceTableVersion): Promise<void> {
  closeCopyForm()
  modelsTarget.value = item
  modelsLoading.value = true
  modelsError.value = ''
  editRows.value = []
  try {
    const detail = await api.getPriceTable(item.id)
    modelsTarget.value = detail
    editRows.value = (detail.models || []).map((row) => ({ ...row }))
  } catch (caught: unknown) {
    modelsError.value = caught instanceof Error ? caught.message : '单价加载失败'
  } finally {
    modelsLoading.value = false
  }
}

function closeModels(): void {
  modelsTarget.value = null
  editRows.value = []
  modelsError.value = ''
  modelsSaving.value = false
}

function addRow(): void {
  editRows.value = [...editRows.value, {
    modelId: '', capability: 'text', provider: 'openai-compatible',
    centsPer1kInputTokens: 0, centsPer1kOutputTokens: 0, centsPerImage: 0, centsPerSecond: 0,
  }]
}

function removeRow(index: number): void {
  editRows.value = editRows.value.filter((_, position) => position !== index)
}

/** 整份覆盖。number 输入经 v-model 会变字符串，必须显式转回数字，否则后端收到 "3" 而非 3。 */
async function saveModels(): Promise<void> {
  const target = modelsTarget.value
  if (!target) return
  const invalid = editRows.value.find((row) => !row.modelId || !row.capability || !row.provider)
  if (invalid) {
    modelsError.value = '模型名、能力、Provider 都不能为空'
    return
  }
  modelsSaving.value = true
  modelsError.value = ''
  try {
    await api.replacePriceTableModels(target.id, editRows.value.map((row) => ({
      modelId: row.modelId,
      capability: row.capability,
      provider: row.provider,
      centsPer1kInputTokens: Number(row.centsPer1kInputTokens) || 0,
      centsPer1kOutputTokens: Number(row.centsPer1kOutputTokens) || 0,
      centsPerImage: Number(row.centsPerImage) || 0,
      centsPerSecond: Number(row.centsPerSecond) || 0,
    })))
    await openModels(target)
  } catch (caught: unknown) {
    modelsError.value = caught instanceof Error ? caught.message : '单价保存失败'
  } finally {
    modelsSaving.value = false
  }
}

async function activateVersion(item: PriceTableVersion): Promise<void> {
  if (!window.confirm(
    `确认激活 ${item.label}？\n`
    + '当前生效版本将转为已退役（保留，用于复现存量 Run 的账）；此后新建的 Run 按新单价计费。')) {
    return
  }
  error.value = ''
  try {
    await api.activatePriceTable(item.id)
    closeModels()
    await loadVersions()
  } catch (caught: unknown) {
    error.value = caught instanceof Error ? caught.message : '激活失败'
  }
}

async function deleteDraft(item: PriceTableVersion): Promise<void> {
  if (!window.confirm(`确认删除 draft ${item.label}？其单价明细将一并移除。`)) return
  error.value = ''
  try {
    await api.deletePriceTableDraft(item.id)
    if (modelsTarget.value?.id === item.id) closeModels()
    await loadVersions()
  } catch (caught: unknown) {
    error.value = caught instanceof Error ? caught.message : '删除失败'
  }
}

function statusLabel(status: PriceTableStatus): string {
  return { draft: '草稿', active: '生效中', retired: '已退役' }[status] || status
}

function formatTime(value: string): string {
  const parsed = new Date(value)
  return Number.isNaN(parsed.getTime()) ? value : parsed.toLocaleString('zh-CN')
}
</script>

<style scoped>
.ai-control-panel { display: grid; gap: 16px; }
.panel-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.panel-heading h3 { margin: 0; color: var(--color-text); letter-spacing: 0; font-size: 1.05rem; }
.panel-heading p { margin: 4px 0 0; color: var(--color-text-muted); font-size: .82rem; }
.primary-command, .secondary-command, .row-actions button { min-height: 34px; padding: 0 12px; border-radius: var(--radius-sm); cursor: pointer; }
.primary-command { border: 1px solid var(--color-accent); background: var(--color-accent); color: var(--color-on-accent); font-weight: 700; }
.secondary-command, .row-actions button { border: 1px solid var(--color-border); background: var(--color-surface); color: var(--color-text-secondary); }
.row-actions .danger-command, .danger-command { color: var(--color-danger); }
.primary-command:disabled { opacity: .5; cursor: wait; }
.empty-state, .error-state { margin: 0; padding: 22px 0; text-align: center; color: var(--color-text-muted); }
.error-state { color: var(--color-danger); }.error-state.compact { grid-column: 1 / -1; padding: 0; text-align: left; }
.version-table-wrap, .price-table-wrap { overflow-x: auto; border: 1px solid var(--color-border); border-radius: var(--radius-md); }
.version-table, .price-table { width: 100%; border-collapse: collapse; font-size: .82rem; }
.version-table { min-width: 720px; }.price-table { min-width: 900px; }
.version-table th, .version-table td, .price-table th, .price-table td { padding: 10px 12px; text-align: left; border-bottom: 1px solid var(--color-border); }
.version-table tr:last-child td, .price-table tr:last-child td { border-bottom: 0; }
.version-table th, .price-table th { color: var(--color-text-muted); background: var(--surface-muted); white-space: nowrap; }
.price-table th small { display: block; margin-top: 2px; font-weight: 400; opacity: .8; }
.version-table td, .price-table td { color: var(--color-text-secondary); }
.version-table strong { color: var(--color-text); }
.version-table .row-active { background: var(--surface-muted); }
.note-cell { max-width: 260px; }
.status-tag { display: inline-block; padding: 3px 7px; border-radius: var(--radius-sm); background: var(--surface-muted); }
.status-active { color: var(--color-success); }.status-draft { color: var(--color-warning); }.status-retired { color: var(--color-text-muted); }
.row-actions { white-space: nowrap; }.row-actions button { min-height: 30px; padding: 0 9px; margin-right: 5px; }
.form-hint { margin: 0 0 12px; color: var(--color-text-muted); font-size: .8rem; }
form { display: grid; grid-template-columns: 1fr 1fr; gap: 13px; }
label { display: grid; gap: 6px; color: var(--color-text-secondary); font-size: .82rem; }
input { width: 100%; box-sizing: border-box; min-height: 34px; padding: 6px 8px; border: 1px solid var(--color-border); border-radius: var(--radius-sm); background: var(--color-surface); color: var(--color-text); font: inherit; }
.price-table input { min-height: 30px; }
.price-table input:disabled { opacity: .7; }
@media (max-width: 700px) { .panel-heading { align-items: flex-start; flex-direction: column; } form { grid-template-columns: 1fr; } form > * { grid-column: 1; } }
</style>
