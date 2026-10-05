import { computed, nextTick, onScopeDispose, ref, watch, type Ref } from 'vue'
import { useAccountSessionStore } from '../stores/account-session'
import type { CreationBrief } from '../types/creation'
import type { CreationVoiceProfile, CreationVoiceRole, CreationVoiceGenre, CreationVoiceSample } from '../types/creation-voice'
import { request, GrasslandHttpError } from './grassland-http'

export const VOICE_ROLES = { consumer: '真实体验用户', merchant: '商家', 'commercial-creator': '商业合作作者', researcher: '资料整理者' } as const
export const VOICE_PLATFORMS = ['zhihu', 'xiaohongshu', 'dianping', 'moments'] as const
export function useCreationVoice(brief: Ref<CreationBrief | null>, platform: () => string, draft: () => string | null | undefined) {
  const session = useAccountSessionStore()
  const role = computed(() => Object.prototype.hasOwnProperty.call(VOICE_ROLES, brief.value?.authorRole ?? '') ? brief.value!.authorRole as CreationVoiceRole : null)
  const supported = computed(() => (VOICE_PLATFORMS as readonly string[]).includes(platform()) && brief.value?.processingMode !== 'format')
  const profile = ref<CreationVoiceProfile | null>(null)
  const rules = ref<string[]>([])
  const samples = ref<CreationVoiceSample[]>([])
  const enabled = ref(false)
  const loading = ref(false), saving = ref(false), extracting = ref(false)
  const error = ref(''), message = ref('')
  const candidates = ref<string[]>([])
  const legacy = ref<string[]>([])
  const editing = ref(false)
  let epoch = 0, readSequence = 0, alive = true
  let readController = new AbortController()
  const contentKey = (value: { enabled: boolean; rules: string[]; samples: CreationVoiceSample[] }) => JSON.stringify([value.enabled, value.rules, value.samples])
  const baseline = ref('')
  const dirty = computed(() => editing.value && baseline.value !== contentKey({ enabled: enabled.value, rules: rules.value, samples: samples.value }))
  const stale = computed(() => brief.value?.voice?.mode === 'profile' && (!profile.value || !profile.value.enabled || brief.value.voice.role !== role.value || brief.value.voice.revision !== profile.value.revision))
  const canUse = computed(() => !!session.ownerAccountId && supported.value && !!role.value && !!profile.value?.enabled && !loading.value && !error.value)
  function ticket() { return { epoch, account: session.capture(), role: role.value } }
  function current(t: ReturnType<typeof ticket>) { return alive && t.epoch === epoch && session.isCurrent(t.account) }
  function url(t: ReturnType<typeof ticket>) { return `/api/creation-voice/${t.role}` }
  function choose(use: boolean) {
    if (use && !canUse.value) return
    brief.value = { processingMode: 'create', ...brief.value, voice: use && profile.value && role.value
      ? { mode: 'profile', role: role.value, revision: profile.value.revision } : { mode: 'none' } }
  }
  function resetEditor() {
    rules.value = [...(profile.value?.rules ?? [])]
    samples.value = (profile.value?.samples ?? []).map(sample => ({ ...sample }))
    enabled.value = profile.value?.enabled ?? false
    baseline.value = contentKey({ enabled: enabled.value, rules: rules.value, samples: samples.value })
  }
  function edit() { resetEditor(); editing.value = true; message.value = ''; candidates.value = [] }
  function confirmDiscard() {
    if (saving.value || extracting.value) return false
    if (dirty.value && !window.confirm('尚未保存文风修改，丢弃这些修改？')) return false
    editing.value = false; candidates.value = []; return true
  }
  async function load() {
    const t = ticket(), sequence = ++readSequence
    if (!t.account.accountId || !t.role || !supported.value) return
    loading.value = true; error.value = ''
    try {
      const data = await request<CreationVoiceProfile>(url(t), { signal: readController.signal })
      if (!current(t) || sequence !== readSequence) return
      profile.value = data
    } catch (e) {
      if (current(t) && sequence === readSequence) error.value = e instanceof Error ? e.message : '文风读取失败，重试'
    } finally { if (current(t) && sequence === readSequence) loading.value = false }
  }
  function validate() {
    const normalized = [...new Set(rules.value.map(rule => rule.trim()))]
    if (normalized.length > 30 || normalized.some(rule => !rule || rule.length > 300)) return '规则最多30条，每条1～300字'
    if (samples.value.length > 5 || samples.value.some(sample => !sample.consent || !sample.text.trim() || sample.text.trim().length > 2000)) return '范文最多5份，每份1～2000字，需确认本人作品或获准使用'
    if (enabled.value && !normalized.length && !samples.value.length) return '启用前请添加规则或范文'
    if (new TextEncoder().encode(JSON.stringify({ rules: normalized, samples: samples.value })).length > 64 * 1024) return '档案内容超过64KiB'
    return ''
  }
  async function save() {
    if (saving.value || loading.value || !role.value || !session.ownerAccountId || !profile.value) return false
    error.value = validate(); if (error.value) return false
    const t = ticket()
    const body = { expectedRevision: profile.value.revision, enabled: enabled.value,
      rules: [...new Set(rules.value.map(rule => rule.trim()))], samples: samples.value.map(s => ({ ...s, text: s.text.trim() })) }
    saving.value = true; message.value = ''
    try {
      let data: CreationVoiceProfile
      try { data = await request<CreationVoiceProfile>(url(t), { method: 'PUT', body: JSON.stringify(body) }) }
      catch (e) {
        if (!current(t)) return false
        if (e instanceof GrasslandHttpError) throw e
        // 网络中断不代表未提交。只读核对，绝不自动重放写入。
        const latest = await request<CreationVoiceProfile>(url(t))
        if (contentKey(latest) !== contentKey(body)) throw Object.assign(new Error('保存结果待核实，请读取最新版本后核对；输入已保留'), { cause: e })
        data = latest
      }
      if (!current(t)) return false
      profile.value = data; resetEditor(); editing.value = false; candidates.value = []
      message.value = '文风已保存'
      if (brief.value?.voice?.mode === 'profile') choose(data.enabled)
      return true
    } catch (e) {
      if (current(t)) error.value = e instanceof GrasslandHttpError && e.status === 409
        ? '文风已在其他页面更新，请读取最新版本后核对。' : e instanceof Error ? e.message : '文风保存失败'
      return false
    } finally { if (current(t)) saving.value = false }
  }
  async function clear() {
    if (saving.value || !window.confirm(`清空${role.value ? VOICE_ROLES[role.value] : ''}的文风规则和范文？`)) return
    rules.value = []; samples.value = []; enabled.value = false
    await save()
  }
  async function preview(original: string, edited: string, reason: string, genre: CreationVoiceGenre) {
    if (extracting.value || !role.value || !supported.value || !session.ownerAccountId) return
    candidates.value = []; error.value = ''
    if (reason !== 'style') { message.value = '事实纠错和一次性要求不保存为长期文风'; return }
    if (!original.trim() || !edited.trim() || original.length > 12000 || edited.length > 12000) { error.value = '请选择1～12000字的原文与修改片段'; return }
    const t = ticket(); extracting.value = true
    try {
      const data = await request<{ candidates: string[] }>(`${url(t)}/preview`, { method: 'POST', body: JSON.stringify({ original, edited, reason, platform: platform(), genre }) })
      if (current(t)) { candidates.value = data.candidates; message.value = data.candidates.length ? '' : '未提取到长期表达习惯' }
    } catch (e) { if (current(t)) error.value = e instanceof Error ? e.message : '文风提炼失败' }
    finally { if (current(t)) extracting.value = false }
  }
  async function loadLegacy() {
    const t = ticket()
    try {
      const data = await request<{ preferences: string[] }>('/api/image-analysis/style-preferences')
      if (current(t)) legacy.value = data.preferences ?? []
    } catch (e) { if (current(t)) error.value = e instanceof Error ? e.message : '旧偏好读取失败' }
  }
  function addRules(selected: string[]) {
    rules.value = [...new Set([...rules.value, ...selected])]
    candidates.value = []; legacy.value = []
  }
  watch(() => [session.epoch, role.value, platform(), draft(), supported.value], () => {
    epoch++; readSequence++; readController.abort(); readController = new AbortController()
    profile.value = null; rules.value = []; samples.value = []; legacy.value = []; candidates.value = []
    error.value = ''; message.value = ''; loading.value = false; saving.value = false; extracting.value = false; editing.value = false
    // Vue 同一轮逐个更新 props：等待平台、身份和简报全部到位再清除无效选择。
    // 私有数据与请求 epoch 仍在上方同步失效，旧账号响应不能回填。
    const t = ticket()
    void nextTick(() => {
      if (current(t) && (!supported.value || !role.value) && brief.value?.voice?.mode === 'profile') choose(false)
    })
    void load()
  }, { immediate: true, flush: 'sync' })
  watch(() => brief.value?.voice, value => {
    if (!value) brief.value = { processingMode: 'create', ...brief.value, voice: { mode: 'none' } }
  }, { immediate: true, flush: 'sync' })
  onScopeDispose(() => { alive = false; epoch++; readController.abort(); rules.value = []; samples.value = []; candidates.value = []; legacy.value = []; profile.value = null })
  return { role, supported, profile, rules, samples, enabled, loading, saving, extracting, error, message, candidates, legacy, editing, dirty, stale, canUse,
    choose, load, edit, confirmDiscard, save, clear, preview, loadLegacy, addRules, resetEditor }
}
