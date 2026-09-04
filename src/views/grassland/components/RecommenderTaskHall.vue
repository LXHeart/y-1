<template>
  <article id="gl-task-hall" class="gl-tile gl-tile-wide">
    <h3>任务大厅</h3>
    <div class="gl-row">
      <input :value="feedFilters.q || ''" type="search" aria-label="搜索任务" name="task-q" autocomplete="off" maxlength="100" placeholder="搜索任务标题或描述"
             @input="$emit('update:feedFilter', 'q', ($event.target as HTMLInputElement).value)"
             @keyup.enter="$emit('load-feed', true)" />
      <label>平台
        <select :value="feedFilters.platform" aria-label="平台筛选" name="task-platform-filter" @change="onPlatformChange(($event.target as HTMLSelectElement).value)">
          <option value="">全部平台</option>
          <option v-for="p in PLATFORM_OPTIONS" :key="p.id" :value="p.id">{{ p.label }}</option>
        </select>
      </label>
      <label>内容形式
        <select :value="feedFilters.contentForm" aria-label="内容形式筛选（随平台裁剪）" name="task-content-form-filter" @change="$emit('update:feedFilter', 'contentForm', ($event.target as HTMLSelectElement).value)">
          <option value="">不限</option>
          <option v-for="opt in contentFormOptions" :key="opt" :value="opt">{{ CONTENT_FORM_LABELS[opt] }}</option>
        </select>
      </label>
      <label>最低赏金 ¥<input :value="feedFilters.minBountyYuan" name="task-min-bounty" autocomplete="off" type="number" min="0" @input="$emit('update:feedFilter', 'minBountyYuan', Number(($event.target as HTMLInputElement).value))" /></label>
      <label>距离
        <select :value="feedFilters.maxDistanceKm" @change="$emit('update:feedFilter', 'maxDistanceKm', Number(($event.target as HTMLSelectElement).value))">
          <option :value="0">不限</option><option :value="1">1 公里内</option><option :value="3">3 公里内</option>
          <option :value="5">5 公里内</option><option :value="10">10 公里内</option><option :value="30">30 公里内</option>
        </select>
      </label>
      <button type="button" :disabled="locating" @click="$emit('use-location')">
        {{ locating ? '定位中…' : feedFilters.latitude == null ? '使用当前位置' : '更新位置' }}
      </button>
      <button type="button" :disabled="feedLoading || loading" @click="$emit('load-feed', true)">查询</button>
    </div>
    <div class="gl-row">
      <input :value="applyNote" aria-label="报名留言（可选）" name="apply-note" autocomplete="off" placeholder="报名留言（可选）" @input="$emit('update:applyNote', ($event.target as HTMLInputElement).value)" />
    </div>
    <p class="gl-hint">大厅只显示已发布且未截止的任务；报名截止后不再接受新报名。</p>
    <p v-if="feedItems.length === 0" class="gl-empty">暂无可报名任务</p>
    <table v-else class="gl-table">
      <thead><tr><th>任务</th><th>门店</th><th>平台</th><th>赏金</th><th>距离</th><th>截止</th><th>操作</th></tr></thead>
      <tbody>
        <tr v-for="t in feedItems" :key="t.id">
          <td>
            <button type="button" class="gl-link" :class="{ active: selectedTaskId === t.id }"
                    @click="$emit('select-task', t.id)">{{ t.title }}</button>
          </td>
          <td>{{ t.store ? [t.store.storeName, t.store.city].filter(Boolean).join(' · ') : '—' }}</td>
          <td>{{ t.platform || '—' }}</td>
          <td>
            <span v-if="t.contentForm === 'interaction'" class="badge badge-warning">点赞互动</span>
            <!-- 任务书 #25：阶梯任务先看档位规则，再显示最高赏金（= 最高档可预留金额） -->
            <CommissionLadderSummary v-if="t.requirements?.commissionLadder" :ladder="t.requirements.commissionLadder" />
            <span v-if="t.freebieDepositCents" class="badge badge-warning"
                  :title="`报名被接受时从钱包预付 ${formatYuan(t.freebieDepositCents)}，达标全额返还`">
              霸王餐 · 需预付 <span class="gl-num">{{ formatYuan(t.freebieDepositCents) }}</span> · 达标全额返还
            </span>
            <template v-else>{{ t.bountyCents ? formatYuan(t.bountyCents) : '无' }}</template>
            <p v-if="t.freebieDepositCents && walletBalanceCents != null && t.freebieDepositCents > walletBalanceCents"
               class="gl-hint gl-freebie-warn">
              押金超过钱包余额 <span class="gl-num">{{ formatYuan(walletBalanceCents) }}</span>，被接受时会因余额不足退回
            </p>
          </td>
          <td>{{ t.distanceKm == null ? '—' : `${t.distanceKm.toFixed(1)}\u00A0km` }}</td>
          <td>{{ t.applicationDeadline ? new Date(t.applicationDeadline).toLocaleString() : '不限' }}</td>
          <td>
            <button type="button" :disabled="loading" @click="$emit('apply', t.id)">报名</button>
          </td>
        </tr>
      </tbody>
    </table>
    <nav v-if="feedItems.length > 0" class="gl-row gl-feed-pager" aria-label="任务大厅分页">
      <button type="button" :disabled="feedLoading || feedPage === 0" @click="$emit('load-feed-prev')">上一页</button>
      <span class="gl-feed-page">第 {{ feedPage + 1 }} 页</span>
      <button type="button" :disabled="feedLoading || !feedHasMore" @click="$emit('load-feed', false)">下一页</button>
    </nav>
  </article>
</template>

<script setup lang="ts">
import { computed, watch } from 'vue'
import CommissionLadderSummary from './CommissionLadderSummary.vue'
import type { Task } from '../../../types/grassland'
import { formatYuan } from '../../../lib/money'
import { AI_PLATFORM_DEFINITIONS, getPlatform, normalizePlatformId } from '../../../config/ai-platform-capabilities'

const props = withDefaults(defineProps<{
  feedItems: Task[]
  feedHasMore: boolean
  feedLoading: boolean
  feedPage: number
  feedFilters: {
    q?: string; platform: string; contentForm: string; minBountyYuan: number; maxDistanceKm: number
    latitude: number | null; longitude: number | null
  }
  applyNote: string
  selectedTaskId: string
  loading: boolean
  locating: boolean
  /** 推荐官钱包余额（分，任务书 #22 软检查）；null/缺省 = 未加载，不做余额提示。 */
  walletBalanceCents?: number | null
}>(), { walletBalanceCents: null })

const emit = defineEmits<{
  'update:feedFilter': [field: string, value: string | number]
  'load-feed': [reset: boolean]
  'load-feed-prev': []
  'update:applyNote': [value: string]
  'select-task': [taskId: string]
  apply: [taskId: string]
  'use-location': []
}>()

/** 平台筛选项与发布表单同源（PRD §2.2 九平台），避免两处枚举漂移。 */
const PLATFORM_OPTIONS = AI_PLATFORM_DEFINITIONS.map((p) => ({ id: p.id, label: p.label }))

/** PRD §2.2 任务内容形式三类（与 MerchantTaskForm 的 CONTENT_FORM_LABELS 同表）。 */
const CONTENT_FORM_LABELS: Readonly<Record<string, string>> = {
  image: '图文种草',
  video: '视频种草',
  interaction: '点赞互动',
}

/**
 * 内容形式选项随平台裁剪（与发布表单同源逻辑，筛选版语义：平台不限 = 三形式全开，
 * 选定平台 = 按平台能力裁剪；点赞互动无需创作内容，所有平台可用）。
 */
function contentFormOptionsFor(platform: string): string[] {
  const platformId = platform ? normalizePlatformId(platform) : null
  if (!platformId) return ['image', 'video', 'interaction']
  const forms = getPlatform(platformId)?.forms
  if (!forms) return ['image', 'video', 'interaction']
  const hasGraphic = forms.some((form) => form.id === 'graphic' || form.id === 'image-text')
  const hasVideo = forms.some((form) => form.id === 'video' || form.id === 'video-text')
  return [hasGraphic ? 'image' : null, hasVideo ? 'video' : null, 'interaction']
    .filter((form): form is string => form !== null)
}

const contentFormOptions = computed<string[]>(() => contentFormOptionsFor(props.feedFilters.platform))

/** 平台切换：当前形式不被新平台支持时清空（筛选器归「不限」，发布表单则落首个可用——语义不同）。 */
function onPlatformChange(value: string): void {
  emit('update:feedFilter', 'platform', value)
  if (props.feedFilters.contentForm && !contentFormOptionsFor(value).includes(props.feedFilters.contentForm)) {
    emit('update:feedFilter', 'contentForm', '')
  }
}

// 兜底：URL 恢复/外部写入的 platform 若使当前形式失效，同样清空（onPlatformChange 只盖用户交互路径）
watch(() => props.feedFilters.platform, (platform) => {
  if (props.feedFilters.contentForm && !contentFormOptionsFor(platform).includes(props.feedFilters.contentForm)) {
    emit('update:feedFilter', 'contentForm', '')
  }
})
</script>

<style scoped>
h3 { margin: 0; font-size: var(--text-base); font-weight: 700; letter-spacing: -0.01em; }

select {
  min-height: 34px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--color-surface);
  color: var(--color-text);
  padding: 4px var(--space-sm);
  font: inherit;
  letter-spacing: 0;
}

.gl-feed-pager { justify-content: flex-end; }
.gl-feed-page { font-size: var(--text-sm); color: var(--color-text-secondary); }

.gl-freebie-warn {
  margin: 4px 0 0;
  color: var(--color-danger);
  font-size: 12px;
}
</style>
