<template>
  <section class="ai-control-panel" aria-labelledby="platform-models-title">
    <header class="panel-heading">
      <div>
        <h3 id="platform-models-title">平台模型</h3>
        <p>按能力维护主模型、备用模型、健康状态和并发上限</p>
      </div>
      <div class="heading-actions">
        <label class="toggle-disabled">
          <input type="checkbox" name="includeDisabled" :checked="includeDisabled"
                 @change="onToggleDisabled(($event.target as HTMLInputElement).checked)" />
          显示已停用
        </label>
        <button type="button" class="primary-command" data-action="add-model" @click="openCreate">新增配置</button>
      </div>
    </header>

    <!-- 任务书 #58：控制面空 = 平台侧 AI fail-closed，冷启动引导按配置顺序提示（可关掉本次会话）。 -->
    <p v-if="emptyGuideVisible" class="empty-guide" data-testid="platform-models-empty-guide">
      尚无平台模型配置，平台侧 AI 调用将不可用——先加受信端点，再添加模型与凭据
      <button type="button" aria-label="关闭引导" data-action="dismiss-empty-guide" @click="emptyGuideDismissed = true">×</button>
    </p>

    <p v-if="error" class="error-state" role="alert">{{ error }}</p>
    <p v-if="loading" class="empty-state">正在加载平台模型...</p>
    <p v-else-if="!error && models.length === 0" class="empty-state">暂无平台模型配置</p>
    <div v-else class="model-table-wrap models-list">
      <table class="model-table">
        <thead><tr><th>能力</th><th>角色</th><th>Provider / 模型</th><th>健康</th><th>并发</th><th>版本</th><th>操作</th></tr></thead>
        <tbody>
          <tr v-for="item in models" :key="item.id" :class="{ 'row-disabled': !item.enabled }">
            <td>{{ capabilityLabel(item.capability) }}</td>
            <td>{{ item.modelRole === 'primary' ? '主模型' : '备用' }}</td>
            <td><strong>{{ item.model }}</strong><small>{{ item.provider }}</small></td>
            <td><span class="health-tag" :class="`health-${item.healthStatus}`">{{ healthLabel(item.healthStatus) }}</span></td>
            <td>{{ item.maxConcurrency == null ? '不限' : item.maxConcurrency }}</td>
            <td>
              v{{ item.version }}
              <small v-if="!item.enabled" class="disabled-tag">已停用</small>
            </td>
            <!-- 停用行不给「修订」：修订走 (capability, role) 两段路径，只会命中当前生效行，
                 在停用行上点它会改到别的行去。要改先恢复。 -->
            <td class="row-actions">
              <template v-if="item.enabled">
                <button type="button" data-action="edit-model" @click="openEdit(item)">修订</button>
                <button type="button" class="danger-command" data-action="disable-model" @click="disableModel(item)">禁用</button>
              </template>
              <template v-else>
                <button type="button" data-action="restore-model" @click="restoreModel(item)">恢复</button>
                <button type="button" class="danger-command" data-action="delete-model" @click="deleteModel(item)">删除</button>
              </template>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- 任务书 #58 决策 B：受信端点表——平台模型 base URL 的 SSRF 白名单，治理台可见可删可停。 -->
    <section class="trusted-origins" aria-labelledby="trusted-origins-title">
      <header class="panel-heading">
        <div>
          <h4 id="trusted-origins-title">受信端点</h4>
          <p>平台模型 base URL 只允许指向这里的端点（scheme://host[:port]，不带路径）</p>
        </div>
        <div class="heading-actions">
          <button type="button" class="primary-command" data-action="add-origin" @click="openOriginCreate">添加端点</button>
        </div>
      </header>
      <p v-if="originError" class="error-state" role="alert">{{ originError }}</p>
      <div v-if="origins.length > 0" class="model-table-wrap">
        <table class="model-table">
          <thead><tr><th>端点</th><th>备注</th><th>状态</th><th>更新时间</th><th>操作</th></tr></thead>
          <tbody>
            <tr v-for="item in origins" :key="item.id" :class="{ 'row-disabled': !item.enabled }">
              <td><strong>{{ item.origin }}</strong></td>
              <td>{{ item.label || '—' }}</td>
              <td><span class="health-tag" :class="item.enabled ? 'health-healthy' : 'health-unhealthy'">{{ item.enabled ? '启用' : '已停用' }}</span></td>
              <td>{{ formatOriginTime(item.updatedAt) }}</td>
              <td class="row-actions">
                <button type="button" data-action="edit-origin" @click="openOriginEdit(item)">编辑</button>
                <button type="button" data-action="toggle-origin" @click="toggleOrigin(item)">{{ item.enabled ? '停用' : '启用' }}</button>
                <button type="button" class="danger-command" data-action="delete-origin" @click="removeOrigin(item)">删除</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <p v-else-if="!originError" class="empty-state">暂无受信端点——保存平台模型 base URL 前需先添加</p>

      <GlModal v-if="originFormMode"
               :title="originFormMode === 'create' ? '添加受信端点' : `编辑端点 · v${originTarget?.version}`"
               @close="closeOriginForm">
        <form id="origin-form" @submit.prevent="submitOrigin">
          <label class="wide-field">端点（HTTPS，如 https://api.example.com）
            <input v-model.trim="originValue" name="origin" required
                   placeholder="https://api.example.com" />
          </label>
          <label>备注（可选）<input v-model.trim="originLabel" name="label" maxlength="64" placeholder="如 MiniMax 图像" /></label>
          <label v-if="originFormMode === 'edit'" class="toggle-disabled">
            <input type="checkbox" name="enabled" :checked="originEnabled"
                   @change="originEnabled = ($event.target as HTMLInputElement).checked" />
            启用
          </label>
          <p v-if="originFormError" class="error-state compact" role="alert">{{ originFormError }}</p>
        </form>
        <template #actions>
          <button type="button" class="secondary-command" @click="closeOriginForm">取消</button>
          <button type="submit" form="origin-form" class="primary-command" :disabled="originSubmitting">{{ originSubmitting ? '保存中...' : '保存端点' }}</button>
        </template>
      </GlModal>
    </section>

    <GlModal v-if="mode" :title="mode === 'create' ? '新增平台模型' : `修订配置 · v${target?.version}`" wide @close="closeForm">
      <form id="model-form" @submit.prevent="submit">
        <label>能力
          <select v-model="capability" name="capability" :disabled="mode === 'edit'">
            <option v-for="option in CAPABILITY_OPTIONS" :key="option.value" :value="option.value">
              {{ option.label }}
            </option>
          </select>
        </label>
        <label>模型角色
          <select v-model="modelRole" name="modelRole" :disabled="mode === 'edit'">
            <option value="primary">主模型</option><option value="backup">备用模型</option>
          </select>
        </label>
        <label class="wide-field">凭据
          <select v-model="credentialId" name="credentialId" required>
            <option value="" disabled>请选择平台通用凭据</option>
            <option v-for="item in credentials" :key="item.id" :value="item.id">
              {{ item.name }} · {{ item.provider }} · {{ item.hasKey ? item.maskedHint : '无密钥（调用将 503）' }}
            </option>
          </select>
        </label>
        <!-- 模型名优先用上游 /models 拉到的列表；拉不到（无密钥/KEK 未配/上游不通）降级为手填，
             不阻断表单——治理台不能因为上游一时不可达就没法改配置。 -->
        <label>模型
          <template v-if="upstreamModels.length > 0">
            <select v-model="modelChoice" name="modelChoice" required>
              <option value="" disabled>请选择模型</option>
              <option v-for="item in upstreamModels" :key="item.id" :value="item.id">{{ item.id }}</option>
              <!-- 上游 /models 常只列文本模型（如 MiniMax）——图像等模型必须留手填出口 -->
              <option value="__manual__">手动输入其他模型…</option>
            </select>
            <input
              v-if="modelChoice === '__manual__'"
              v-model.trim="model"
              name="model"
              required
              maxlength="128"
              placeholder="填写上游模型名（如 image-01）"
            >
          </template>
          <input v-else v-model.trim="model" name="model" required maxlength="128"
                 :placeholder="modelsHint || '手动填写模型名'" />
        </label>
        <!-- provider / baseUrl 不再作为字段出现：它们由所选凭据唯一决定（运行时是
             COALESCE(credential.base_url, config.base_url)），摆成只读框只是噪音。
             凭据选项里已带 provider，选中后在下方一行摘要里复述地址，够确认用。 -->
        <p v-if="selectedCredential" class="credential-summary wide-field">
          将写入 <strong>{{ selectedCredential.provider }}</strong> · {{ selectedCredential.baseUrl }}
        </p>
        <label>并发上限<input v-model="maxConcurrency" name="maxConcurrency" type="number" min="1" step="1" placeholder="留空表示不限" /></label>
        <label>健康状态
          <select v-model="healthStatus" name="healthStatus">
            <option value="healthy">健康</option><option value="degraded">降级</option><option value="unhealthy">不可用</option>
          </select>
        </label>
        <p v-if="formError" class="error-state compact" role="alert">{{ formError }}</p>
      </form>
      <template #actions>
        <button type="button" class="secondary-command" @click="closeForm">取消</button>
        <button type="submit" form="model-form" class="primary-command" :disabled="submitting">{{ submitting ? '保存中...' : '保存修订' }}</button>
      </template>
    </GlModal>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useAiControlPlane } from '../composables/useAiControlPlane'
import GlModal from './GlModal.vue'
import { PLATFORM_CAPABILITIES } from '../types/ai-control-plane'
import type {
  PlatformCapability, PlatformModelConfig, PlatformModelHealth, PlatformModelRole,
  PlatformProviderCredential, PlatformTrustedOrigin,
} from '../types/ai-control-plane'

/** 能力下拉只列控制面真正解析的能力（2026-08-30 起含 image_generation）；标签与表格列共用。 */
const CAPABILITY_LABELS: Record<string, string> = {
  text: '文本', voice: '语音', retrieval: '检索', image_edit: '图片编辑', content_safety: '内容安全',
  vision: '视觉理解', image_generation: '图片生成', video_understanding: '视频理解',
  video_generation: '视频生成',
}
const CAPABILITY_OPTIONS: Array<{ value: PlatformCapability; label: string }> =
  PLATFORM_CAPABILITIES.map((value) => ({ value, label: CAPABILITY_LABELS[value] || value }))

const api = useAiControlPlane()
const models = ref<PlatformModelConfig[]>([])
const credentials = ref<PlatformProviderCredential[]>([])
const loading = ref(false)
const error = ref('')
const formError = ref('')
const submitting = ref(false)
const mode = ref<'create' | 'edit' | null>(null)
const target = ref<PlatformModelConfig | null>(null)
const capability = ref<PlatformCapability>('text')
const modelRole = ref<PlatformModelRole>('primary')
const credentialId = ref('')
const model = ref('')
/** 上游列表选择值；'__manual__' 时以手填 model 为准（上游 /models 缺图像模型的出口）。 */
const modelChoice = ref('')
const maxConcurrency = ref('')
const healthStatus = ref<PlatformModelHealth>('healthy')

const includeDisabled = ref(false)
const upstreamModels = ref<Array<{ id: string; ownedBy?: string }>>([])

// ---------- 受信端点（任务书 #58 决策 B）----------
const origins = ref<PlatformTrustedOrigin[]>([])
const originError = ref('')
const originFormError = ref('')
const originSubmitting = ref(false)
const originFormMode = ref<'create' | 'edit' | null>(null)
const originTarget = ref<PlatformTrustedOrigin | null>(null)
const originValue = ref('')
const originLabel = ref('')
const originEnabled = ref(true)
const emptyGuideDismissed = ref(false)

const emptyGuideVisible = computed(() =>
  !loading.value && !error.value && models.value.length === 0 && !emptyGuideDismissed.value)

async function loadOrigins(): Promise<void> {
  try {
    origins.value = [...await api.listTrustedOrigins()]
    originError.value = ''
  } catch (caught: unknown) {
    origins.value = []
    originError.value = caught instanceof Error ? caught.message : '受信端点加载失败'
  }
}

function formatOriginTime(value: string): string {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '—' : date.toLocaleString('zh-CN')
}

function resetOriginForm(): void {
  originTarget.value = null
  originValue.value = ''
  originLabel.value = ''
  originEnabled.value = true
  originFormError.value = ''
}

function openOriginCreate(): void { resetOriginForm(); originFormMode.value = 'create' }
function openOriginEdit(item: PlatformTrustedOrigin): void {
  resetOriginForm()
  originFormMode.value = 'edit'
  originTarget.value = item
  originValue.value = item.origin
  originLabel.value = item.label
  originEnabled.value = item.enabled
}
function closeOriginForm(): void { resetOriginForm(); originFormMode.value = null }

async function submitOrigin(): Promise<void> {
  const currentMode = originFormMode.value
  const currentTarget = originTarget.value
  if (!currentMode || (currentMode === 'edit' && !currentTarget)) return
  originSubmitting.value = true
  originFormError.value = ''
  try {
    if (currentMode === 'create') {
      await api.createTrustedOrigin({ origin: originValue.value, label: originLabel.value })
    } else if (currentTarget) {
      await api.updateTrustedOrigin(currentTarget.id, {
        origin: originValue.value,
        label: originLabel.value,
        enabled: originEnabled.value,
        expectedVersion: currentTarget.version,
      })
    }
    closeOriginForm()
    await loadOrigins()
  } catch (caught: unknown) {
    originFormError.value = caught instanceof Error ? caught.message : '受信端点保存失败'
  } finally {
    originSubmitting.value = false
  }
}

/** 停用/启用走同一 PUT（乐观锁带当前版本；409 提示刷新重试）。 */
async function toggleOrigin(item: PlatformTrustedOrigin): Promise<void> {
  originError.value = ''
  try {
    await api.updateTrustedOrigin(item.id, {
      origin: item.origin, label: item.label, enabled: !item.enabled, expectedVersion: item.version,
    })
    await loadOrigins()
  } catch (caught: unknown) {
    originError.value = caught instanceof Error ? caught.message : '受信端点状态更新失败'
  }
}

/** 删除端点可能让引用它的模型行校验失败——确认文案里说清后果。 */
async function removeOrigin(item: PlatformTrustedOrigin): Promise<void> {
  if (!window.confirm(`确认删除受信端点 ${item.origin}？\n仍指向它的平台模型配置将在下次调用时被拒绝。`)) return
  originError.value = ''
  try {
    await api.deleteTrustedOrigin(item.id)
    await loadOrigins()
  } catch (caught: unknown) {
    originError.value = caught instanceof Error ? caught.message : '受信端点删除失败'
  }
}

/** 上游列表到达/清空时同步选择值：model 在列表内 → 选中它；否则手动态（手填值保留）。 */
watch(upstreamModels, (list) => {
  if (!list.length) return
  if (!model.value) {
    modelChoice.value = ''
    return
  }
  modelChoice.value = list.some((item) => item.id === model.value) ? model.value : '__manual__'
})
const modelsHint = ref('')

const selectedCredential = computed(() =>
  credentials.value.find((item) => item.id === credentialId.value) || null)

/**
 * 选中凭据后拉上游模型列表。失败只留提示、清空列表 → 模板降级为手填输入框。
 * 刻意不写进 formError：那是提交态错误，拉列表失败不该让表单看起来已经出错。
 */
async function loadUpstreamModels(id: string): Promise<void> {
  upstreamModels.value = []
  modelsHint.value = ''
  if (!id) return
  try {
    upstreamModels.value = [...await api.listSelectedModels(id)]
    if (upstreamModels.value.length === 0) {
      modelsHint.value = '该凭据尚未勾选模型，请先到「平台通用凭据」点「获取模型」勾选'
    }
  } catch (caught: unknown) {
    modelsHint.value = caught instanceof Error ? `${caught.message}，请手动填写` : '勾选模型读取失败，请手动填写'
  }
}

watch(credentialId, (next, previous) => {
  if (next === previous) return
  // 换凭据后旧模型名大概率在新上游不存在，清掉避免提交一个上游不认的名字
  if (previous) { model.value = ''; modelChoice.value = '' }
  void loadUpstreamModels(next)
})

onMounted(() => { void loadModels(); void loadCredentials(); void loadOrigins() })

/**
 * 凭据列表加载失败不阻断模型列表——表格仍可读，只是无法新建/修订。
 * 单独的 error 态会掩盖模型列表本身的错误，故只在提交时以 formError 提示。
 */
async function loadCredentials(): Promise<void> {
  try {
    credentials.value = [...await api.listCredentials()]
  } catch {
    credentials.value = []
  }
}

async function loadModels(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    models.value = [...await api.listModels(includeDisabled.value)]
  } catch (caught: unknown) {
    models.value = []
    error.value = caught instanceof Error ? caught.message : '平台模型加载失败'
  } finally {
    loading.value = false
  }
}

function onToggleDisabled(next: boolean): void {
  includeDisabled.value = next
  void loadModels()
}

/**
 * 恢复一行已停用配置。该能力+角色已有生效行时后端回 409——原样透出，
 * 让运营知道要先停用现有的那行，而不是静默顶掉线上配置。
 */
async function restoreModel(item: PlatformModelConfig): Promise<void> {
  error.value = ''
  try {
    await api.restoreModel(item.id)
    await loadModels()
  } catch (caught: unknown) {
    error.value = caught instanceof Error ? caught.message : '平台模型恢复失败'
  }
}

/** 硬删已停用行。不可逆，故二次确认里说清「审计仍在 history」这个边界。 */
async function deleteModel(item: PlatformModelConfig): Promise<void> {
  const label = `${capabilityLabel(item.capability)}·${item.modelRole === 'primary' ? '主模型' : '备用模型'}`
  if (!window.confirm(
    `确认永久删除 ${label} 的 v${item.version}（${item.model}）？\n`
    + '配置行将从库中移除且不可恢复；变更审计仍保留在 history 表。')) {
    return
  }
  error.value = ''
  try {
    await api.deleteModel(item.id)
    await loadModels()
  } catch (caught: unknown) {
    error.value = caught instanceof Error ? caught.message : '平台模型删除失败'
  }
}

function resetForm(): void {
  target.value = null; capability.value = 'text'; modelRole.value = 'primary'; credentialId.value = ''
  model.value = ''; modelChoice.value = ''; maxConcurrency.value = ''; healthStatus.value = 'healthy'; formError.value = ''
  upstreamModels.value = []; modelsHint.value = ''
}

function openCreate(): void { resetForm(); mode.value = 'create' }
function openEdit(item: PlatformModelConfig): void {
  resetForm(); mode.value = 'edit'; target.value = item
  capability.value = item.capability as PlatformCapability
  modelRole.value = item.modelRole; model.value = item.model
  // 反查凭据：配置行只回 provider/baseUrl，没有 credentialId。匹配不到（凭据已停用或
  // 该行由旧表单隐式建的空壳凭据支撑）就留空，逼用户显式选一个，而不是静默沿用不存在的凭据。
  credentialId.value = credentials.value.find(
    (candidate) => candidate.provider === item.provider && candidate.baseUrl === item.baseUrl)?.id || ''
  maxConcurrency.value = item.maxConcurrency?.toString() || ''; healthStatus.value = item.healthStatus
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
  if (!credentialId.value) {
    formError.value = '请选择凭据'
    return
  }
  submitting.value = true
  formError.value = ''
  // 只发 credentialId：provider/baseUrl 由后端从该凭据带出（resolveDestination），
  // 不再手抄地址，也不会触发按 (provider, baseUrl) 反查时的隐式建凭据。
  const mutableFields = {
    credentialId: credentialId.value,
    model: (upstreamModels.value.length > 0 && modelChoice.value !== '__manual__')
      ? modelChoice.value
      : model.value,
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
  return CAPABILITY_LABELS[value] || value
}
function healthLabel(value: PlatformModelHealth): string {
  return { healthy: '健康', degraded: '降级', unhealthy: '不可用' }[value]
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
.row-actions .danger-command { color: var(--color-danger); }.primary-command:disabled { opacity: .5; cursor: wait; }
.empty-state, .error-state { margin: 0; padding: 22px 0; text-align: center; color: var(--color-text-muted); }
.error-state { color: var(--color-danger); }.error-state.compact { grid-column: 1 / -1; padding: 0; text-align: left; }
.model-table-wrap { overflow-x: auto; border: 1px solid var(--color-border); border-radius: var(--radius-md); }
.model-table { width: 100%; min-width: 820px; border-collapse: collapse; font-size: .82rem; }
.model-table th, .model-table td { padding: 11px 12px; text-align: left; border-bottom: 1px solid var(--color-border); }
.model-table tr:last-child td { border-bottom: 0; }.model-table th { color: var(--color-text-muted); background: var(--surface-muted); }
.model-table td { color: var(--color-text-secondary); }.model-table strong, .model-table small { display: block; }.model-table strong { color: var(--color-text); }.model-table small { color: var(--color-text-muted); margin-top: 2px; }
.row-actions { white-space: nowrap; }.row-actions button { min-height: 30px; padding: 0 9px; margin-right: 5px; }
.health-tag { display: inline-block; padding: 3px 7px; border-radius: var(--radius-sm); background: var(--surface-muted); }.health-healthy { color: var(--color-success); }.health-degraded { color: var(--color-warning); }.health-unhealthy { color: var(--color-danger); }
.heading-actions { display: flex; align-items: center; gap: 12px; }
.toggle-disabled { display: flex; align-items: center; gap: 6px; color: var(--color-text-secondary); font-size: .82rem; cursor: pointer; }
.toggle-disabled input { width: auto; min-height: 0; margin: 0; }
.model-table .row-disabled td { opacity: .55; }
.disabled-tag { display: block; margin-top: 2px; color: var(--color-warning); }
.credential-summary { margin: 0; color: var(--color-text-muted); font-size: .8rem; }
.credential-summary strong { color: var(--color-text-secondary); }
.empty-guide {
  display: flex; align-items: center; justify-content: space-between; gap: 12px;
  margin: 0; padding: 10px 12px; border: 1px solid var(--color-warning);
  border-radius: var(--radius-sm); color: var(--color-text-secondary); font-size: .82rem;
}
.empty-guide button { min-height: 28px; padding: 0 8px; border: 1px solid var(--color-border); border-radius: var(--radius-sm); background: var(--color-surface); color: var(--color-text-secondary); cursor: pointer; }
.trusted-origins { display: grid; gap: 12px; padding-top: 16px; border-top: 1px solid var(--color-border); }
.trusted-origins .panel-heading h4 { margin: 0; font-size: .95rem; color: var(--color-text); }
form { display: grid; grid-template-columns: 1fr 1fr; gap: 13px; }.wide-field { grid-column: 1 / -1; }
label { display: grid; gap: 6px; color: var(--color-text-secondary); font-size: .82rem; }
input, select { width: 100%; box-sizing: border-box; min-height: 38px; padding: 8px 10px; border: 1px solid var(--color-border); border-radius: var(--radius-sm); background: var(--color-surface); color: var(--color-text); font: inherit; }
@media (max-width: 700px) { .panel-heading { align-items: flex-start; flex-direction: column; } form { grid-template-columns: 1fr; } form > * { grid-column: 1; } }
</style>
