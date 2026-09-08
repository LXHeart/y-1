<template>
  <div v-show="active" class="ops-panel">
    <div class="ops-filters">
      <button type="button" class="ops-quiet" :disabled="grassland.loading.value" @click="refresh">刷新</button>
    </div>
    <p class="ops-hint">
      履约提交时内容安全词库存疑（low/medium，未达拦截线）的评论/备注。复核结论独立记录：
      判违规只在商家交付物列表打标记（平台内容安全 ≠ 业务验收，接不接受仍由商家决定）。
    </p>
    <p v-if="loaded && comments.length === 0" class="ops-hint">当前没有待复核的条目。</p>

    <section v-for="row in comments" :key="row.submissionId" class="ops-item">
      <div class="ops-item-head">
        <strong>{{ row.taskTitle }}</strong>
        <span class="ops-pos">推荐官 <code>{{ shortId(row.recommenderAccountId) }}</code> · {{ row.platform || '-' }} · {{ row.field === 'note' ? '备注' : '评论' }}</span>
      </div>
      <dl class="ops-meta">
        <div><dt>{{ row.field === 'note' ? '备注原文' : '评论文本' }}</dt><dd class="ops-comment-text">{{ row.commentText }}</dd></div>
        <div><dt>词库命中</dt><dd>{{ row.findings.map((f) => `${f.category}(${f.severity})`).join('、') || '-' }}</dd></div>
        <div><dt>交付状态</dt><dd>{{ row.submissionStatus }}</dd></div>
        <div><dt>提交时间</dt><dd>{{ time(row.submittedAt) }}</dd></div>
      </dl>
      <div class="ops-actions ops-review-actions">
        <input
          v-model="notes[row.submissionId]"
          class="ops-review-note"
          type="text"
          maxlength="500"
          placeholder="复核备注（判违规必填）"
        />
        <button
          type="button"
          :disabled="grassland.loading.value"
          @click="review(row, 'confirmed')"
        >确认无问题</button>
        <button
          type="button"
          class="ops-danger"
          :disabled="grassland.loading.value"
          @click="review(row, 'violation')"
        >判违规</button>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
/**
 * 评论复核面板（任务书 #94 自 OpsConsole 原样拆出，行为零变更：800 行门禁下的分层归位）。
 *
 * 可见性与懒加载由父级 `active` 驱动（首次切入拉取一次，此后常驻内存）；提示统一
 * emit 给父级 say()。grassland 以 prop 传入，与控制台共用同一实例（loading 全局联动）。
 */
import { ref, watch } from 'vue'
import type { useGrassland } from '../../../composables/useGrassland'
import type { OpsCommentReview } from '../../../types/grassland'

const props = defineProps<{
  active: boolean
  grassland: ReturnType<typeof useGrassland>
}>()

const emit = defineEmits<{ notice: [message: string, bad?: boolean] }>()

const comments = ref<OpsCommentReview[]>([])
const loaded = ref(false)
const notes = ref<Record<string, string>>({})

watch(() => props.active, (on) => {
  if (on && !loaded.value) void refresh()
})

async function refresh(): Promise<void> {
  const result = await props.grassland.listOpsCommentReviews()
  if (result) comments.value = [...result.items]
  loaded.value = true
}

async function review(row: OpsCommentReview, decision: 'confirmed' | 'violation'): Promise<void> {
  const note = (notes.value[row.submissionId] || '').trim()
  if (decision === 'violation' && !note) {
    emit('notice', '判定违规必须填写原因', true)
    return
  }
  const result = await props.grassland.reviewOpsComment(row.submissionId, row.field, decision, note || undefined)
  if (result) {
    notes.value[row.submissionId] = ''
    emit('notice', decision === 'violation' ? '已判定违规，商家侧将看到标记' : '已复核确认无问题')
    await refresh()
  } else {
    emit('notice', props.grassland.error.value || '评论复核失败', true)
  }
}

function shortId(id: string | null): string {
  return id ? `${id.slice(0, 8)}…` : '—'
}

function time(value: string | null): string {
  return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—'
}
</script>

<style scoped>
@import '../ops-console-shared.css';
</style>
