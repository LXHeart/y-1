<script setup lang="ts">
import { computed } from 'vue'
import type { RecommenderProfile, RecommenderReputation } from '../types/grassland'

/**
 * 推荐官等级徽章 + 数据面板（PRD 五「等级」/ 六「基础信息 + 数据面板 + 社交平台 + 标签」）。
 *
 * 纯展示组件——数据由父组件批量取好传进来，因为商家侧要对整张报名列表做筛选与排序，
 * 每行各自去拉数据既慢又没法参与筛选判断。
 *
 * 两条如实展示的口径：
 * - 「暂无评分」与「评分 0 分」是两回事，null 一律显示成前者（后端刻意回 null 而不是 0）。
 * - 粉丝量是推荐官**自报**的，界面必须写明，否则商家会当成平台核验过的数据来决策。
 */

const props = defineProps<{
  reputation: RecommenderReputation | null
  profile?: RecommenderProfile | null
  /** 紧凑模式：只显示徽章与三项核心指标（用于表格行内）。 */
  compact?: boolean
}>()

const completionPercent = computed(() =>
  props.reputation ? `${Math.round(props.reputation.completionRate * 100)}%` : '—')

const scoreText = computed(() => {
  const rep = props.reputation
  if (!rep || rep.averageScore === null) return '暂无评分'
  return `${rep.averageScore.toFixed(1)} 分（${rep.ratingCount} 次）`
})

/** 秒 → 人话。样本为空时是 null，说明这人还没提交过履约，不能显示成 0 秒。 */
const responseText = computed(() => {
  const seconds = props.reputation?.averageResponseSeconds
  if (seconds === null || seconds === undefined) return '暂无数据'
  if (seconds < 60) return `${Math.round(seconds)} 秒`
  if (seconds < 3600) return `${Math.round(seconds / 60)} 分钟`
  if (seconds < 86400) return `${(seconds / 3600).toFixed(1)} 小时`
  return `${(seconds / 86400).toFixed(1)} 天`
})

const tags = computed(() => [
  ...(props.profile?.contentTags || []),
  ...(props.profile?.domainTags || []),
])
</script>

<template>
  <div class="rep">
    <div class="rep-head">
      <span class="rep-level" :class="`rep-${reputation?.level || 'Lv1'}`">
        {{ reputation ? `${reputation.level} ${reputation.levelTitle}` : '等级加载中' }}
      </span>
      <span v-if="profile?.displayName" class="rep-name">{{ profile.displayName }}</span>
    </div>

    <dl class="rep-stats">
      <div><dt>完成</dt><dd>{{ reputation ? reputation.completedCount : '—' }} 单</dd></div>
      <div><dt>完成率</dt><dd>{{ completionPercent }}</dd></div>
      <div><dt>评分</dt><dd>{{ scoreText }}</dd></div>
      <div v-if="!compact"><dt>平均响应</dt><dd>{{ responseText }}</dd></div>
    </dl>

    <template v-if="!compact">
      <p v-if="profile?.bio" class="rep-bio">{{ profile.bio }}</p>
      <p v-if="tags.length > 0" class="rep-tags">
        <span v-for="t in tags" :key="t" class="rep-tag">{{ t }}</span>
      </p>
      <ul v-if="profile && profile.socialAccounts.length > 0" class="rep-social">
        <li v-for="s in profile.socialAccounts" :key="`${s.platform}-${s.handle}`">
          {{ s.platform }}<span v-if="s.handle"> · {{ s.handle }}</span>
          <span v-if="s.followers !== null"> · 粉丝 {{ s.followers }}（自报）</span>
        </li>
      </ul>
      <p v-if="profile && !profile.displayName && !profile.bio && tags.length === 0" class="rep-empty">
        这位推荐官还没有填写资料。
      </p>
    </template>
  </div>
</template>

<style scoped>
.rep { display: flex; flex-direction: column; gap: 6px; }
.rep-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.rep-level { font-size: 11px; padding: 2px 8px; border-radius: var(--radius-lg); background: var(--color-surface-strong); white-space: nowrap; }
.rep-Lv2 { background: color-mix(in srgb, var(--color-accent) 18%, transparent); }
.rep-Lv3 { background: color-mix(in srgb, var(--color-accent) 30%, transparent); }
.rep-Lv4 { background: color-mix(in srgb, var(--color-success) 26%, transparent); color: var(--color-success); }
.rep-Lv5 { background: color-mix(in srgb, var(--color-success) 40%, transparent); color: var(--color-success); font-weight: 600; }
.rep-name { font-size: 13px; font-weight: 600; }
.rep-stats { display: flex; gap: 14px; flex-wrap: wrap; margin: 0; font-size: 12px; }
.rep-stats div { display: flex; gap: 4px; }
.rep-stats dt { opacity: 0.6; margin: 0; }
.rep-stats dd { margin: 0; }
.rep-bio { margin: 0; font-size: 12px; opacity: 0.8; }
.rep-tags { display: flex; gap: 6px; flex-wrap: wrap; margin: 0; }
.rep-tag { font-size: 11px; padding: 1px 7px; border-radius: var(--radius-lg); background: var(--color-surface-strong); }
.rep-social { list-style: none; margin: 0; padding: 0; font-size: 12px; opacity: 0.8; display: flex; flex-direction: column; gap: 2px; }
.rep-empty { margin: 0; font-size: 12px; opacity: 0.55; }
</style>
