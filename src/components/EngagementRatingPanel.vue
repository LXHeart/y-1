<script setup lang="ts">
import { ref, watch } from 'vue'
import { useGrassland } from '../composables/useGrassland'
import type { EngagementRating } from '../types/grassland'

/**
 * 履约评分面板（PRD 六「评分」——商家确认履约后对推荐官打 1-5 星）。
 *
 * 一个面板两个视角，用 `role` 区分（与 {@link EngagementSubmissionPanel} 同构）：
 * - 商家：确认履约后（`canRate`）可评分；已评过则只读展示。
 * - 推荐官：只读——看到商家给自己的评分（后端 visibility 允许本人查看）。
 *
 * 评分必须**先确认履约**（否则后端 409「尚未确认履约」），且一次履约只能评一次（重复 409）。
 * 这两道闸门都在后端，前端不自行判定 confirmedAt——列表里没有该字段，硬猜会错。
 * `canRate` 只控制「要不要显示评分表单」，真正能不能评由后端说了算。
 */

const props = defineProps<{
  taskId: string
  applicationId: string
  role: 'merchant' | 'recommender'
  /** 商家侧：是否已确认履约（决定是否显示评分表单）。推荐官侧忽略。 */
  canRate?: boolean
}>()

const grassland = useGrassland()

const rating = ref<EngagementRating | null>(null)
const notice = ref('')
const selectedScore = ref(0)
const hoverScore = ref(0)
const comment = ref('')

async function refresh(): Promise<void> {
  // 未评价时后端返回 data:null（不是 404），这里如实落成 null。
  const existing = await grassland.getEngagementRating(props.taskId, props.applicationId)
  rating.value = existing
}

watch(() => [props.taskId, props.applicationId], refresh, { immediate: true })

async function submit(): Promise<void> {
  if (selectedScore.value < 1 || selectedScore.value > 5) return
  notice.value = ''
  const created = await grassland.rateEngagement(
    props.taskId, props.applicationId, selectedScore.value, comment.value.trim() || undefined)
  if (!created) return   // 409（未确认 / 已评过）等由 error 条呈现
  rating.value = created
  selectedScore.value = 0
  comment.value = ''
  notice.value = '评分已提交'
}
</script>

<template>
  <section class="rate">
    <p v-if="grassland.error.value" class="rate-alert rate-err" role="alert">{{ grassland.error.value }}</p>
    <p v-if="notice" class="rate-alert rate-ok">{{ notice }}</p>

    <!-- 已评过分：两个视角都只读展示 -->
    <div v-if="rating" class="rate-existing">
      <span class="rate-stars" :aria-label="`评分 ${rating.score} 星`">
        <span v-for="n in 5" :key="n" :class="n <= rating.score ? 'rate-on' : 'rate-off'">★</span>
      </span>
      <span class="rate-score">{{ rating.score }} 分</span>
      <span v-if="rating.comment" class="rate-comment">{{ rating.comment }}</span>
    </div>

    <!-- 未评价 -->
    <template v-else>
      <!-- 商家且已确认履约：可评分 -->
      <div v-if="role === 'merchant' && canRate" class="rate-form">
        <div class="rate-stars" role="radiogroup" aria-label="评分（1-5 星）">
          <button
            v-for="n in 5" :key="n" type="button" role="radio"
            :aria-checked="(hoverScore || selectedScore) === n"
            :class="(hoverScore || selectedScore) >= n ? 'rate-on' : 'rate-off'"
            @mouseenter="hoverScore = n" @mouseleave="hoverScore = 0" @click="selectedScore = n"
          >★</button>
        </div>
        <input v-model="comment" placeholder="评价（可选）" />
        <button type="button" :disabled="grassland.loading.value || selectedScore < 1" @click="submit">提交评分</button>
      </div>
      <!-- 商家但尚未确认：提示须先确认 -->
      <p v-else-if="role === 'merchant'" class="rate-hint">确认履约后可对本次合作评分。</p>
      <!-- 推荐官侧未收到评分 -->
      <p v-else class="rate-hint">商家尚未评分。</p>
    </template>
  </section>
</template>

<style scoped>
.rate { display: flex; flex-direction: column; gap: 6px; padding-top: 8px; border-top: 1px dashed var(--color-border); }
.rate-alert { margin: 0; padding: 6px 10px; border-radius: 6px; font-size: 12px; }
.rate-err { background: color-mix(in srgb, var(--color-danger) 14%, transparent); color: var(--color-danger); }
.rate-ok { background: color-mix(in srgb, var(--color-success) 14%, transparent); color: var(--color-success); }
.rate-existing { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; font-size: 13px; }
.rate-stars { display: inline-flex; gap: 2px; font-size: 16px; }
.rate-stars button { font-size: 16px; line-height: 1; }
.rate-on { color: var(--color-accent); }
.rate-off { color: var(--color-border); }
.rate-score { font-weight: 600; }
.rate-comment { opacity: 0.8; font-size: 12px; }
.rate-form { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.rate-form input { flex: 1 1 200px; padding: 6px 10px; border: 1px solid var(--color-border); background: var(--color-surface); color: var(--color-text); border-radius: 6px; font-size: 13px; }
button { padding: 6px 14px; border: 1px solid var(--color-border); background: transparent; color: var(--color-text); border-radius: 6px; cursor: pointer; font-size: 13px; }
button:hover:not(:disabled) { border-color: var(--color-border-hover); background: var(--color-surface-hover); }
button:disabled { opacity: 0.5; cursor: not-allowed; }
.rate-hint { margin: 0; font-size: 12px; opacity: 0.6; }
</style>
