<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useGrassland } from '../composables/useGrassland'
import type { EngagementSubmission, SubmissionStatus } from '../types/grassland'

/**
 * 履约交付物面板（PRD 九第一步：推荐官提交凭证 → 商家核验）。
 *
 * 同一份数据两个视角，故用 `role` 区分动作而不是写两个组件：
 * - 推荐官：提交发布链接 + 说明；被退回后可修改重交；已有待核验的一份时不能再提交。
 * - 商家：看到链接与说明，可「退回补交」并写原因；确认履约由父组件的按钮触发（确认即核验通过）。
 *
 * 商家侧的「确认履约」现在**必须**先有待核验的交付物，否则后端 409——这正是本面板存在的意义：
 * 此前确认是凭空点的。
 */

const props = defineProps<{
  taskId: string
  applicationId: string
  role: 'merchant' | 'recommender'
}>()

const emit = defineEmits<{ changed: [] }>()

const grassland = useGrassland()

const submissions = ref<EngagementSubmission[]>([])
const notice = ref('')
const contentUrl = ref('')
const note = ref('')
const rejectNote = ref('')

const STATUS_LABEL: Record<SubmissionStatus, string> = {
  submitted: '待商家核验',
  accepted: '已核验通过',
  rejected: '已退回',
}

const pending = computed(() => submissions.value.find((s) => s.status === 'submitted') || null)
const canSubmit = computed(() => !pending.value && contentUrl.value.trim().length > 0)

async function refresh(): Promise<void> {
  const list = await grassland.listDeliverables(props.taskId, props.applicationId)
  if (list) submissions.value = list
}

watch(() => [props.taskId, props.applicationId], refresh, { immediate: true })

async function submit(): Promise<void> {
  if (!canSubmit.value) return
  notice.value = ''
  const created = await grassland.submitDeliverable(
    props.taskId, props.applicationId, contentUrl.value.trim(), note.value.trim() || undefined)
  if (!created) return
  contentUrl.value = ''
  note.value = ''
  notice.value = '已提交，等待商家核验'
  await refresh()
  emit('changed')
}

async function reject(submission: EngagementSubmission): Promise<void> {
  notice.value = ''
  const rejected = await grassland.rejectDeliverable(
    props.taskId, props.applicationId, submission.id, rejectNote.value.trim() || undefined)
  if (!rejected) return
  rejectNote.value = ''
  notice.value = '已退回，推荐官可修改后重新提交'
  await refresh()
  emit('changed')
}
</script>

<template>
  <section class="sub">
    <p v-if="grassland.error.value" class="sub-alert sub-err" role="alert">{{ grassland.error.value }}</p>
    <p v-if="notice" class="sub-alert sub-ok">{{ notice }}</p>

    <p v-if="submissions.length === 0" class="sub-hint">
      {{ role === 'recommender' ? '尚未提交履约凭证。发布内容后把链接交上来，商家核验通过才会进入结算。'
        : '推荐官尚未提交履约凭证——在此之前无法确认履约。' }}
    </p>

    <ul v-else class="sub-list">
      <li v-for="s in submissions" :key="s.id">
        <div class="sub-main">
          <a :href="s.contentUrl" target="_blank" rel="noopener noreferrer">{{ s.contentUrl }}</a>
          <span class="sub-tag" :class="`sub-${s.status}`">{{ STATUS_LABEL[s.status] || s.status }}</span>
        </div>
        <p v-if="s.note" class="sub-meta">说明：{{ s.note }}</p>
        <p v-if="s.reviewNote" class="sub-meta sub-reject">退回原因：{{ s.reviewNote }}</p>
        <div v-if="role === 'merchant' && s.status === 'submitted'" class="sub-row">
          <input v-model="rejectNote" placeholder="退回原因（建议填写）" />
          <button type="button" :disabled="grassland.loading.value" @click="reject(s)">退回补交</button>
        </div>
      </li>
    </ul>

    <div v-if="role === 'recommender'" class="sub-form">
      <div class="sub-row">
        <input v-model="contentUrl" placeholder="发布链接（https://…）" />
      </div>
      <div class="sub-row">
        <input v-model="note" placeholder="补充说明（可选）" />
        <button type="button" :disabled="grassland.loading.value || !canSubmit" @click="submit">提交履约</button>
      </div>
      <p class="sub-hint">
        <span v-if="pending">已有一份待商家核验，等核验结果或被退回后才能重新提交。</span>
        <span v-else>链接须为 http(s)；提交后商家可核验通过（进入结算）或退回补交。</span>
      </p>
    </div>
  </section>
</template>

<style scoped>
.sub { display: flex; flex-direction: column; gap: 8px; padding-top: 8px; border-top: 1px dashed var(--color-border); }
.sub-alert { margin: 0; padding: 6px 10px; border-radius: 6px; font-size: 12px; }
.sub-err { background: color-mix(in srgb, var(--color-danger) 14%, transparent); color: var(--color-danger); }
.sub-ok { background: color-mix(in srgb, var(--color-success) 14%, transparent); color: var(--color-success); }
.sub-hint { margin: 0; font-size: 12px; opacity: 0.62; }
.sub-list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 8px; }
.sub-list li { display: flex; flex-direction: column; gap: 4px; padding: 8px 10px; border: 1px solid var(--color-border); border-radius: 8px; }
.sub-main { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; font-size: 13px; }
.sub-main a { color: var(--color-accent); word-break: break-all; }
.sub-meta { margin: 0; font-size: 12px; opacity: 0.7; }
.sub-reject { color: var(--color-danger); opacity: 0.9; }
.sub-tag { font-size: 11px; padding: 1px 6px; border-radius: 4px; background: var(--color-surface-strong); }
.sub-submitted { background: color-mix(in srgb, var(--color-accent) 22%, transparent); }
.sub-accepted { background: color-mix(in srgb, var(--color-success) 22%, transparent); }
.sub-rejected { background: color-mix(in srgb, var(--color-danger) 22%, transparent); }
.sub-row { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.sub-form { display: flex; flex-direction: column; gap: 6px; }
input { flex: 1 1 220px; padding: 6px 10px; border: 1px solid var(--color-border); background: var(--color-surface); color: var(--color-text); border-radius: 6px; font-size: 13px; }
button { padding: 6px 14px; border: 1px solid var(--color-border); background: transparent; color: var(--color-text); border-radius: 6px; cursor: pointer; font-size: 13px; }
button:hover:not(:disabled) { border-color: var(--color-border-hover); background: var(--color-surface-hover); }
button:disabled { opacity: 0.5; cursor: not-allowed; }
</style>
