<script setup lang="ts">
import type { RecommenderMatch } from '../../../types/grassland'

defineProps<{
  items: RecommenderMatch[]
  eligibleCount: number
  scoringVersion: string
  loading: boolean
  invitingAccountId: string
}>()

defineEmits<{
  refresh: []
  invite: [match: RecommenderMatch]
}>()

function evidenceText(match: RecommenderMatch, key: string): string {
  const dimension = match.dimensions.find((item) => item.key === key)
  if (!dimension) return '0'
  return `${dimension.score}/${dimension.maxScore}`
}
</script>

<template>
  <section class="match-panel" aria-label="智能推荐官排序">
    <header class="match-head">
      <div>
        <h4>推荐官智能排序</h4>
        <p>确定性评分 · {{ scoringVersion || 'deterministic-v1' }} · 候选 {{ eligibleCount }}</p>
      </div>
      <button type="button" :disabled="loading" @click="$emit('refresh')">刷新</button>
    </header>

    <p v-if="loading && items.length === 0" class="gl-empty">正在计算推荐顺序…</p>
    <p v-else-if="items.length === 0" class="gl-empty">暂无符合任务等级要求的历史推荐官</p>
    <div v-else class="match-scroll">
      <table class="gl-table match-table">
        <thead>
          <tr><th>推荐官</th><th>总分</th><th>六维得分</th><th>推荐理由</th><th>操作</th></tr>
        </thead>
        <tbody>
          <tr v-for="match in items" :key="match.accountId">
            <td><code>{{ match.accountId.slice(0, 8) }}…</code><small>{{ match.level }}</small></td>
            <td><strong class="match-total">{{ match.totalScore }}</strong><span>/100</span></td>
            <td>
              <!-- title 仅供鼠标悬停；aria-label 给读屏完整维度名，视觉文本保持缩写不扩行 -->
              <div class="match-dimensions" aria-label="六维得分">
                <span title="平台契合度" :aria-label="`平台契合度 ${evidenceText(match, 'platformFit')}`">平台 {{ evidenceText(match, 'platformFit') }}</span>
                <span title="推荐官等级" :aria-label="`推荐官等级 ${evidenceText(match, 'level')}`">等级 {{ evidenceText(match, 'level') }}</span>
                <span title="历史完成率" :aria-label="`历史完成率 ${evidenceText(match, 'completionRate')}`">完成 {{ evidenceText(match, 'completionRate') }}</span>
                <span title="平均评分" :aria-label="`平均评分 ${evidenceText(match, 'averageRating')}`">评分 {{ evidenceText(match, 'averageRating') }}</span>
                <span title="首次交付响应速度" :aria-label="`首次交付响应速度 ${evidenceText(match, 'responseSpeed')}`">响应 {{ evidenceText(match, 'responseSpeed') }}</span>
                <span title="近期平台活跃" :aria-label="`近期平台活跃 ${evidenceText(match, 'recentActivity')}`">活跃 {{ evidenceText(match, 'recentActivity') }}</span>
              </div>
              <details>
                <summary>查看计算证据</summary>
                <dl>
                  <template v-for="dimension in match.dimensions" :key="dimension.key">
                    <dt>{{ dimension.label }} · {{ dimension.score }}/{{ dimension.maxScore }}</dt>
                    <dd>{{ dimension.reason }}</dd>
                  </template>
                </dl>
              </details>
            </td>
            <td><ul><li v-for="reason in match.reasons" :key="reason">{{ reason }}</li></ul></td>
            <td>
              <span v-if="match.invitation" class="match-invited">已邀请</span>
              <button
                v-else type="button"
                :disabled="loading || Boolean(invitingAccountId)"
                @click="$emit('invite', match)"
                >{{ invitingAccountId === match.accountId ? '邀请中…' : '邀请' }}</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </section>
</template>

<style scoped>
.match-panel { display: grid; gap: var(--space-sm); margin: var(--space-md) 0 var(--space-md); }
.match-head { display: flex; align-items: center; justify-content: space-between; gap: var(--space-sm); }
.match-head h4 { margin: 0; font-size: var(--type-body); }
.match-head p { margin: var(--space-xxs) 0 0; color: var(--color-text-secondary); font-size: var(--type-caption); }
.match-scroll { overflow-x: auto; }
.match-table { min-width: 900px; }
.match-table td { vertical-align: top; }
.match-table td:first-child { white-space: nowrap; }
.match-table small { display: block; margin-top: var(--space-xxs); color: var(--color-text-secondary); }
.match-total { font-size: var(--type-numeric); color: var(--color-accent-2); }
.match-table td:nth-child(2) span { color: var(--color-text-secondary); font-size: var(--type-caption); }
.match-dimensions { display: grid; grid-template-columns: repeat(3, minmax(82px, 1fr)); gap: var(--space-xxs); }
.match-dimensions span { padding: var(--space-xxs) var(--space-xxs); border: 1px solid var(--color-border); border-radius: var(--radius-xs); font-size: var(--type-caption); white-space: nowrap; }
details { margin-top: var(--space-xs); font-size: var(--type-caption); }
summary { cursor: pointer; color: var(--color-accent-2); }
dl { margin: var(--space-xxs) 0 0; }
dt { font-weight: var(--weight-heading); }
dd { margin: 0 0 var(--space-xxs); color: var(--color-text-secondary); }
ul { margin: 0; padding-left: var(--space-md); }
.match-invited { color: var(--color-success); font-size: var(--type-caption); font-weight: var(--weight-heading); }
</style>
