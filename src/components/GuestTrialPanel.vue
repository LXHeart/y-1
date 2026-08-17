<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useGuestTrial } from '../composables/useGuestTrial'
import type { GuestTrialCapability } from '../composables/useGuestTrial'

/**
 * 游客免费体验面板（任务书 #36 / ADR-D14 R7）：仅未登录时由 AI 中心渲染。
 * 三个迷你表单（主题/文案/图片）+ 额度徽标 + 用尽后的登录引导（文案带注册赠送积分数，读后端配置）。
 * 登录用户不显示本组件（正常功能面不变）。
 */

const emit = defineEmits<{ 'request-login': [] }>()

const { quota, loading, error, refreshQuota, runTrial } = useGuestTrial()

const activeTab = ref<GuestTrialCapability>('article-titles')
const topic = ref('')
const content = ref('')
const imageBase64 = ref('')
const imageName = ref('')
const running = ref(false)
const tabNotice = ref('')
/** 最近一次结果（按能力渲染）；null = 未跑过。 */
const results = ref<Partial<Record<GuestTrialCapability, Record<string, unknown>>>>({})
/** 最近一次错误码（quota_exhausted 触发登录引导弹层）。 */
const runError = ref<{ capability: GuestTrialCapability; code: string; message: string } | null>(null)

const TABS: ReadonlyArray<{ id: GuestTrialCapability; label: string; hint: string }> = [
  { id: 'article-titles', label: '起标题', hint: '输入主题，生成 5 个种草标题' },
  { id: 'content-score', label: '文案评分', hint: '粘贴文案，5 维评分 + 优化建议' },
  { id: 'image-review', label: '探店点评', hint: '上传 1 张照片，生成点评草稿' },
]

const activeTabMeta = computed(() => TABS.find((t) => t.id === activeTab.value)!)
const activeQuota = computed(() => quota.value?.capabilities?.[activeTab.value] ?? null)
const canRun = computed(() => {
  if (running.value || (activeQuota.value?.remaining ?? 1) <= 0) return false
  if (activeTab.value === 'article-titles') return topic.value.trim().length > 0
  if (activeTab.value === 'content-score') return content.value.trim().length > 0
  return imageBase64.value.length > 0
})

const latestResult = computed<Record<string, unknown> | null>(() =>
  results.value[activeTab.value] ?? null)
const titleItems = computed(() =>
  (latestResult.value?.titles as { title?: string; hook?: string }[] | undefined) ?? [])
const scoreDimensions = computed(() =>
  (latestResult.value?.dimensions as { label?: string; score?: number; advice?: string }[] | undefined) ?? [])
const reviewText = computed(() => String(latestResult.value?.review ?? ''))

const loginPromptVisible = computed(() => runError.value?.code === 'quota_exhausted')
const signupBonusCredits = computed(() => quota.value?.signupBonusCredits ?? 0)

onMounted(() => {
  void refreshQuota()
})

function switchTab(id: GuestTrialCapability): void {
  activeTab.value = id
  tabNotice.value = ''
  runError.value = null
}

async function run(): Promise<void> {
  if (!canRun.value) return
  running.value = true
  tabNotice.value = ''
  runError.value = null
  const body: Record<string, string> = activeTab.value === 'article-titles'
    ? { topic: topic.value.trim() }
    : activeTab.value === 'content-score'
      ? { content: content.value.trim() }
      : { imageBase64: imageBase64.value }
  const outcome = await runTrial(activeTab.value, body)
  running.value = false
  if (outcome.result) {
    results.value = { ...results.value, [activeTab.value]: outcome.result }
    void refreshQuota()
    return
  }
  if (outcome.errorCode === 'quota_exhausted') {
    runError.value = { capability: activeTab.value, code: outcome.errorCode, message: outcome.errorMessage ?? '' }
    void refreshQuota()
    return
  }
  tabNotice.value = outcome.errorMessage ?? '生成失败，请稍后再试'
}

function onPickImage(event: Event): void {
  const input = event.target as HTMLInputElement
  input.value = ''
  const file = (input.files || [])[0]
  if (!file) return
  if (file.size > 4 * 1024 * 1024) {
    tabNotice.value = '图片不能超过 4MB'
    return
  }
  const reader = new FileReader()
  reader.onload = () => {
    const dataUrl = String(reader.result ?? '')
    const comma = dataUrl.indexOf(',')
    imageBase64.value = comma >= 0 ? dataUrl.slice(comma + 1) : ''
    imageName.value = file.name
    tabNotice.value = ''
  }
  reader.readAsDataURL(file)
}
</script>

<template>
  <section class="trial" aria-labelledby="guest-trial-title">
    <header class="trial-head">
      <div>
        <p class="trial-kicker">免费体验</p>
        <h3 id="guest-trial-title">不注册，先试试草场的 AI 能力</h3>
      </div>
      <p v-if="activeQuota" class="trial-quota">今日剩余 {{ activeQuota.remaining }}/{{ activeQuota.limit }} 次</p>
      <p v-else-if="loading" class="trial-quota">额度加载中…</p>
    </header>

    <div class="trial-tabs" role="tablist" aria-label="免费体验能力">
      <button
        v-for="tab in TABS"
        :key="tab.id"
        type="button"
        role="tab"
        :aria-selected="activeTab === tab.id"
        :class="{ active: activeTab === tab.id }"
        @click="switchTab(tab.id)"
      >{{ tab.label }}</button>
    </div>
    <p class="trial-hint">{{ activeTabMeta.hint }}</p>

    <p v-if="error" class="trial-alert">{{ error }}</p>

    <div class="trial-form">
      <input
        v-if="activeTab === 'article-titles'"
        v-model="topic"
        placeholder="主题，如：citywalk 咖啡店探店"
        :disabled="running"
        @keyup.enter="run"
      />
      <textarea
        v-else-if="activeTab === 'content-score'"
        v-model="content"
        placeholder="粘贴你的种草文案…"
        rows="5"
        :disabled="running"
      />
      <label v-else class="trial-file">
        <input type="file" accept="image/*" :disabled="running" @change="onPickImage" />
        <span>{{ imageName || '选择一张探店照片（≤4MB）' }}</span>
      </label>
      <button type="button" :disabled="!canRun" @click="run">{{ running ? '生成中…' : '免费生成' }}</button>
    </div>

    <p v-if="tabNotice" class="trial-alert">{{ tabNotice }}</p>

    <div v-if="loginPromptVisible" class="trial-login" role="alert">
      <p>今日免费次数已用完。注册即送 {{ signupBonusCredits }} 积分，解锁全部 AI 能力。</p>
      <button type="button" @click="emit('request-login')">免费注册</button>
    </div>

    <div v-if="latestResult" class="trial-result">
      <template v-if="activeTab === 'article-titles'">
        <ul>
          <li v-for="(item, i) in titleItems" :key="i">
            <strong>{{ item.title }}</strong>
            <span v-if="item.hook">{{ item.hook }}</span>
          </li>
        </ul>
      </template>
      <template v-else-if="activeTab === 'content-score'">
        <ul>
          <li v-for="(dim, i) in scoreDimensions" :key="i">
            <strong>{{ dim.label }} {{ dim.score }}/10</strong>
            <span>{{ dim.advice }}</span>
          </li>
        </ul>
      </template>
      <template v-else>
        <p class="trial-review">{{ reviewText }}</p>
      </template>
    </div>

    <p class="trial-hint">体验次数每天重置；生成内容不保存，注册后可使用完整能力并留存草稿。</p>
  </section>
</template>

<style scoped>
.trial { display: flex; flex-direction: column; gap: 10px; padding: 14px; border: 1px solid var(--color-border); border-radius: 10px; background: var(--color-surface); }
.trial-head { display: flex; align-items: flex-end; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.trial-kicker { margin: 0; font-size: 12px; color: var(--color-accent); letter-spacing: 0.08em; }
.trial-head h3 { margin: 2px 0 0; font-size: 16px; }
.trial-quota { margin: 0; font-size: 13px; opacity: 0.75; }
.trial-tabs { display: flex; gap: 6px; flex-wrap: wrap; }
.trial-tabs button { padding: 6px 14px; border: 1px solid var(--color-border); border-radius: 999px; background: transparent; color: var(--color-text); cursor: pointer; font-size: 13px; }
.trial-tabs button.active { border-color: var(--color-accent); color: var(--color-accent); background: color-mix(in srgb, var(--color-accent) 12%, transparent); }
.trial-hint { margin: 0; font-size: 12px; opacity: 0.62; }
.trial-form { display: flex; gap: 8px; flex-wrap: wrap; align-items: flex-start; }
.trial-form input, .trial-form textarea { flex: 1 1 260px; padding: 8px 10px; border: 1px solid var(--color-border); border-radius: 6px; background: var(--color-surface-strong, var(--color-surface)); color: var(--color-text); font: inherit; font-size: 13px; }
.trial-form button { padding: 8px 16px; border: 1px solid var(--color-accent); border-radius: 6px; background: var(--color-accent); color: var(--color-surface); cursor: pointer; font-size: 13px; }
.trial-form button:disabled { opacity: 0.5; cursor: not-allowed; }
.trial-file { display: inline-flex; align-items: center; gap: 6px; cursor: pointer; font-size: 13px; flex: 1 1 260px; }
.trial-file input { display: none; }
.trial-file span { padding: 8px 12px; border: 1px dashed var(--color-border); border-radius: 6px; }
.trial-alert { margin: 0; padding: 7px 11px; border-radius: 6px; font-size: 13px; background: color-mix(in srgb, var(--color-danger) 14%, transparent); color: var(--color-danger); }
.trial-login { display: flex; align-items: center; justify-content: space-between; gap: 10px; flex-wrap: wrap; padding: 10px 12px; border-radius: 8px; background: color-mix(in srgb, var(--color-accent) 14%, transparent); }
.trial-login p { margin: 0; font-size: 13px; }
.trial-login button { padding: 7px 16px; border: none; border-radius: 6px; background: var(--color-accent); color: var(--color-surface); cursor: pointer; font-size: 13px; }
.trial-result { border-top: 1px dashed var(--color-border); padding-top: 8px; }
.trial-result ul { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 6px; }
.trial-result li { display: flex; flex-direction: column; gap: 2px; font-size: 13px; }
.trial-result li span { opacity: 0.7; font-size: 12px; }
.trial-review { margin: 0; font-size: 13px; line-height: 1.6; }
</style>
