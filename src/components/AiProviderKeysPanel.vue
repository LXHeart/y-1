<template>
  <section class="ai-control-panel" aria-labelledby="ai-provider-keys-title">
    <header class="panel-heading">
      <div>
        <h3 id="ai-provider-keys-title">个人模型密钥</h3>
        <p v-if="isMerchantView">当前是商家身份，AI 模型由组织统一配置，个人密钥不参与</p>
        <p v-else>每个能力可单独选择用自己的模型还是系统默认。密钥加密保存，页面只显示掩码</p>
      </div>
      <button
        v-if="!isMerchantView"
        type="button"
        class="primary-command"
        data-action="add-key"
        @click="openCreate"
      >添加密钥</button>
    </header>

    <!-- 商家身份：整个面板只读。个人密钥数据仍在，只是该视角下不参与路由（D9） -->
    <p v-if="isMerchantView" class="merchant-notice" data-testid="merchant-readonly-notice">
      商家身份下的模型配置在「工作台 → 组织管理」维护。切换到推荐官身份可管理自己的密钥。
    </p>

    <!-- 四个能力开关 + 计费主体常驻显示（D11 / D21）。用户能一眼看出谁在付钱 -->
    <div v-else class="switch-band">
      <p class="switch-band-note">「自定义模型」用你在下方登记的密钥（不扣积分）；「平台内置模型」用管理后台统一配置的模型。</p>
      <p v-if="preferenceError" class="error-state compact" role="alert">{{ preferenceError }}</p>
      <article v-for="row in capabilityRows" :key="row.capability" class="switch-row">
        <div class="switch-main">
          <strong>{{ capabilityLabel(row.capability) }}</strong>
          <span class="billing-tag" :class="`billing-${row.subject}`">{{ billingLabel(row.subject) }}</span>
        </div>
        <label class="switch-toggle">
          <input
            type="checkbox"
            role="switch"
            :data-action="`toggle-${row.capability}`"
            :checked="row.useOwnKey"
            :disabled="preferenceSaving === row.capability || !preferencesLoaded"
            @change="onToggle(row)"
          />
          <span>{{ row.useOwnKey ? '使用自定义模型' : '使用平台内置模型' }}</span>
        </label>
      </article>
    </div>

    <p v-if="error" class="error-state" role="alert">{{ error }}</p>
    <p v-if="loading" class="empty-state">正在加载密钥...</p>
    <p v-else-if="!error && personalKeys.length === 0" class="empty-state">暂无个人模型密钥</p>
    <div v-else class="key-list" :class="{ 'key-list-readonly': isMerchantView }">
      <article v-for="key in personalKeys" :key="key.id" class="key-row">
        <div class="key-main">
          <strong>{{ capabilityLabel(key.capability) }} · {{ key.model || '默认模型' }}</strong>
          <span>{{ key.provider }} · {{ key.maskedHint }}</span>
          <small>{{ key.baseUrl }}</small>
        </div>
        <div v-if="!isMerchantView" class="row-actions">
          <button type="button" data-action="edit-key" @click="openEdit(key)">编辑</button>
          <button type="button" data-action="rotate-key" @click="openRotate(key)">轮换</button>
          <button type="button" class="danger-command" data-action="disable-key" @click="disableKey(key)">停用</button>
        </div>
      </article>
    </div>

    <div v-if="mode && !isMerchantView" class="form-band">
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
import { useActiveIdentity } from '../composables/useActiveIdentity'
import type {
  AiBillingSubject,
  AiProviderCapability,
  AiProviderKey,
  AiProviderPreference,
} from '../types/ai-control-plane'

type FormMode = 'create' | 'edit' | 'rotate' | null

/** 四个能力的固定顺序，与后端 GET /api/ai/preferences 的返回顺序一致。 */
const CAPABILITIES: AiProviderCapability[] = ['text', 'image', 'image_generation', 'video_generation']

const api = useAiControlPlane()
const { activeSide, identitiesLoaded } = useActiveIdentity()

/**
 * 商家身份下个人密钥不参与路由（D9），故面板整体只读——给出可操作的指向而不是静默失效。
 * 这是 activeSide 的客户端镜像；后端的权威判定在 edge 断言（orgId 非空 ⟺ merchant）。
 *
 * <b>必须等 identitiesLoaded</b>：`activeSide` 的模块级默认值是 `'merchant'`，不等装载完成
 * 会让推荐官先闪一屏错误的只读态。未确认身份前按可编辑处理（与改造前一致）。
 */
const isMerchantView = computed(() => identitiesLoaded.value && activeSide.value === 'merchant')

const preferences = ref<AiProviderPreference[]>([])
const preferencesLoaded = ref(false)
const preferenceError = ref('')
const preferenceSaving = ref<AiProviderCapability | null>(null)

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

/** 该能力是否已配有效个人密钥——决定「用我的模型」是否真的能生效。 */
function hasKeyFor(capability: AiProviderCapability): boolean {
  return personalKeys.value.some((key) => key.capability === capability)
}

/**
 * 计费主体（D21）。三态而非两态，因为「开关 on 但没配密钥」必须显示成平台——
 * 否则用户以为自己在付费，实际在扣积分。
 */
function billingSubjectFor(capability: AiProviderCapability, useOwnKey: boolean): AiBillingSubject {
  if (isMerchantView.value) return 'organization'
  return useOwnKey && hasKeyFor(capability) ? 'own-key' : 'platform'
}

const capabilityRows = computed(() => CAPABILITIES.map((capability) => {
  const preference = preferences.value.find((item) => item.capability === capability)
  // 无偏好行即 on（D14）——与后端 defaultFor 同口径
  const useOwnKey = preference ? preference.useOwnKey : true
  return {
    capability,
    useOwnKey,
    version: preference ? preference.version : 0,
    subject: billingSubjectFor(capability, useOwnKey),
  }
}))

onMounted(() => {
  void loadKeys()
  if (!isMerchantView.value) void loadPreferences()
})

async function loadPreferences(): Promise<void> {
  preferenceError.value = ''
  try {
    preferences.value = [...await api.listPreferences()]
    preferencesLoaded.value = true
  } catch (caught: unknown) {
    preferences.value = []
    preferencesLoaded.value = false
    preferenceError.value = caught instanceof Error ? caught.message : '模型开关加载失败'
  }
}

/**
 * D21：关闭开关会把计费主体从「我的模型 0 积分」变成「平台默认 扣积分」——这是唯一一个
 * 点一下就改变「谁付钱」的开关，而积分有购买订单、有对账，误关的代价是用户在不知情下消耗积分。
 * 故只在<b>关闭</b>方向二次确认；打开方向是省钱，不拦。
 */
async function onToggle(row: { capability: AiProviderCapability; useOwnKey: boolean; version: number }): Promise<void> {
  const next = !row.useOwnKey
  if (!next && !window.confirm(
    `关闭后「${capabilityLabel(row.capability)}」将使用平台内置模型（管理后台统一配置），并按积分计费。确认关闭？`)) {
    // 用户取消：重载以把 DOM 上已翻转的 checkbox 拉回真实状态
    await loadPreferences()
    return
  }
  preferenceSaving.value = row.capability
  preferenceError.value = ''
  try {
    const saved = await api.setPreference(row.capability, {
      useOwnKey: next,
      expectedVersion: row.version,
    })
    preferences.value = [
      ...preferences.value.filter((item) => item.capability !== row.capability),
      saved,
    ]
  } catch (caught: unknown) {
    preferenceError.value = caught instanceof AiControlPlaneError && caught.status === 409
      ? '开关已被其他会话修改，已重新加载'
      : caught instanceof Error ? caught.message : '模型开关保存失败'
    await loadPreferences()
  } finally {
    preferenceSaving.value = null
  }
}

function billingLabel(subject: AiBillingSubject): string {
  return {
    'own-key': '自定义模型 · 不扣积分',
    organization: '组织模型 · 不扣积分',
    platform: '平台内置 · 按积分计费',
  }[subject]
}

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
.primary-command, .secondary-command, .row-actions button, .form-heading button { min-height: 34px; padding: 0 12px; border-radius: var(--radius-sm); cursor: pointer; }
.primary-command { border: 1px solid var(--color-accent); background: var(--color-accent); color: var(--color-on-accent); font-weight: 700; }
.secondary-command, .row-actions button, .form-heading button { border: 1px solid var(--color-border); background: var(--color-surface); color: var(--color-text-secondary); }
.row-actions .danger-command { color: var(--color-danger); }
.primary-command:disabled { opacity: .5; cursor: wait; }
.empty-state, .error-state { margin: 0; padding: 22px 0; text-align: center; color: var(--color-text-muted); }
.error-state { color: var(--color-danger); }.error-state.compact { grid-column: 1 / -1; padding: 0; text-align: left; }
.merchant-notice { margin: 0; padding: 13px 14px; border: 1px solid var(--color-border); border-radius: var(--radius-md); background: var(--surface-muted); color: var(--color-text-secondary); font-size: .84rem; }
.switch-band { display: grid; gap: 1px; background: var(--color-border); border: 1px solid var(--color-border); border-radius: var(--radius-md); overflow: hidden; }
.switch-band .error-state.compact { background: var(--color-surface); padding: 11px 14px; }
.switch-band-note { grid-column: 1 / -1; margin: 0; padding: 11px 14px; background: var(--color-surface); color: var(--color-text-muted); font-size: .8rem; line-height: 1.5; }
.switch-row { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 12px 14px; background: var(--color-surface); }
.switch-main { display: flex; align-items: center; gap: 9px; flex-wrap: wrap; min-width: 0; }
.switch-main strong { color: var(--color-text); font-size: .88rem; }
.billing-tag { display: inline-block; padding: 2px 8px; border-radius: var(--radius-pill); font-size: .74rem; background: var(--surface-muted); }
.billing-own-key, .billing-organization { color: var(--color-text-secondary); }
.billing-platform { color: var(--color-warning); }
.switch-toggle { display: inline-flex; align-items: center; gap: 7px; color: var(--color-text-secondary); font-size: .8rem; white-space: nowrap; cursor: pointer; }
.switch-toggle input { cursor: pointer; }
.switch-toggle input:disabled { cursor: wait; }
.key-list-readonly { opacity: .72; }
.key-list { display: grid; border: 1px solid var(--color-border); border-radius: var(--radius-md); overflow: hidden; }
.key-row { padding: 13px 14px; border-bottom: 1px solid var(--color-border); }.key-row:last-child { border-bottom: 0; }
.key-main { display: grid; gap: 3px; min-width: 0; }.key-main strong { color: var(--color-text); }.key-main span, .key-main small { color: var(--color-text-muted); overflow-wrap: anywhere; }
.form-band { padding-top: 16px; border-top: 1px solid var(--color-border); }
form { display: grid; grid-template-columns: 1fr 1fr; gap: 13px; }
.form-heading, .form-actions, .wide-field { grid-column: 1 / -1; }
label { display: grid; gap: 6px; color: var(--color-text-secondary); font-size: .82rem; }
input, select { width: 100%; box-sizing: border-box; min-height: 38px; padding: 8px 10px; border: 1px solid var(--color-border); border-radius: var(--radius-sm); background: var(--color-surface); color: var(--color-text); font: inherit; }
.form-actions { justify-content: flex-end; }
@media (max-width: 700px) { .panel-heading, .key-row { align-items: flex-start; flex-direction: column; }.row-actions { width: 100%; }.row-actions button { flex: 1; } form { grid-template-columns: 1fr; } form > * { grid-column: 1; } }
</style>
