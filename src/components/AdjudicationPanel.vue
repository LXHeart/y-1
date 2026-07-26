<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { useGrassland } from '../composables/useGrassland'
import type { AdjudicationSnapshot, Judge, VoteChoice } from '../types/grassland'

/**
 * 审判看板——把 Slice 6C 建的审判能力暴露到 UI。
 *
 * 分区按角色显示：
 * - 所有人：状态 / 轮次 / 面板进度 / 计票条
 * - 审判官（已入池且在本轮面板）：投票
 * - 当事方（decided 态）：上诉
 * - 客服：终审覆盖（⚠️ 当前必然 403，见下方说明）
 */

const props = defineProps<{ disputeId: string }>()

const grassland = useGrassland()

const snapshot = ref<AdjudicationSnapshot | null>(null)
const judge = ref<Judge | null>(null)
const voteRationale = ref('')
const csDecision = ref<'for_merchant' | 'for_recommender'>('for_merchant')
const localNotice = ref('')
const reauthPassword = ref('')
/** 本次会话重认证时刻（本地展示用；权威值在 session，由断言透传给后端）。 */
const reauthAt = ref('')

const isVoting = computed(() => snapshot.value?.status === 'voting')
const isDecided = computed(() => snapshot.value?.status === 'decided')
const isFinal = computed(() => snapshot.value?.status === 'final')
const isEnrolledJudge = computed(() => judge.value?.active === true)

const tallies = computed(() => snapshot.value?.tallies ?? null)

/**
 * 窗口倒计时。后端给的 remainingSeconds 是拉取瞬间的快照，本地每秒递减做平滑显示，
 * 避免为了走秒去轮询后端。归零后提示「等待系统处理」——真正的到期由 Temporal Timer 驱动，
 * 可能有秒级偏差，故不据此断言状态已变。
 */
const remaining = ref<number | null>(null)
let ticker: ReturnType<typeof setInterval> | undefined

function startTicker(): void {
  if (ticker) clearInterval(ticker)
  ticker = setInterval(() => {
    if (remaining.value !== null && remaining.value > 0) remaining.value -= 1
  }, 1000)
}

onUnmounted(() => {
  if (ticker) clearInterval(ticker)
})

const windowLabel = computed(() => {
  const phase = snapshot.value?.window?.phase
  if (phase === 'vote') return '投票窗口'
  if (phase === 'appeal') return '上诉窗口'
  return ''
})

/** 剩余时长的人类可读形式（1天2小时 / 5分30秒 / 已到期）。 */
const remainingText = computed(() => {
  const value = remaining.value
  if (value === null) return ''
  if (value <= 0) return '已到期，等待系统处理'
  const days = Math.floor(value / 86400)
  const hours = Math.floor((value % 86400) / 3600)
  const minutes = Math.floor((value % 3600) / 60)
  const seconds = value % 60
  if (days > 0) return `剩余 ${days} 天 ${hours} 小时`
  if (hours > 0) return `剩余 ${hours} 小时 ${minutes} 分`
  if (minutes > 0) return `剩余 ${minutes} 分 ${seconds} 秒`
  return `剩余 ${seconds} 秒`
})

/** 计票条宽度（按面板满员算，未投票部分留白 → 直观看出投票进度）。 */
function barWidth(count: number): string {
  const size = tallies.value?.panelSize ?? 0
  if (size <= 0) return '0%'
  return `${Math.round((count / size) * 100)}%`
}

const statusLabel = computed(() => {
  const map: Record<string, string> = {
    open: '已受理（未启动审判）',
    voting: '投票中',
    decided: '已判决（上诉窗口内）',
    appealed: '已上诉（待客服终审）',
    final: '已终局',
  }
  return map[snapshot.value?.status ?? ''] || snapshot.value?.status || '—'
})

const decisionLabel = (value: string | null): string => {
  if (!value) return '—'
  if (value === 'for_merchant') return '商家方胜诉'
  if (value === 'for_recommender') return '推荐官方胜诉'
  return value
}

async function refresh(): Promise<void> {
  const snap = await grassland.getAdjudication(props.disputeId)
  if (snap) {
    snapshot.value = snap
    // 以后端值为准重置倒计时（本地 tick 只做平滑，不作权威）
    remaining.value = snap.window?.remainingSeconds ?? null
    if (remaining.value !== null) startTicker()
  }
  judge.value = await grassland.getMyJudgeStatus()
}

watch(() => props.disputeId, refresh, { immediate: true })

async function startAdjudication(): Promise<void> {
  localNotice.value = ''
  const started = await grassland.startAdjudication(props.disputeId)
  if (!started) return
  snapshot.value = started
  localNotice.value = `审判已启动（第 ${started.round} 轮，面板 ${started.panel.size} 人）`
}

async function enroll(): Promise<void> {
  localNotice.value = ''
  const enrolled = await grassland.enrollAsJudge()
  if (!enrolled) return
  judge.value = enrolled
  localNotice.value = '已加入审判官池，后续争议可能抽中你'
}

async function leave(): Promise<void> {
  const left = await grassland.leaveJudgePool()
  if (!left) return
  judge.value = left
  localNotice.value = '已退出审判官池'
}

async function vote(choice: VoteChoice): Promise<void> {
  localNotice.value = ''
  const cast = await grassland.castVote(props.disputeId, choice, voteRationale.value.trim() || undefined)
  if (!cast) return
  voteRationale.value = ''
  localNotice.value = '投票已记录（每轮一票，不可更改）'
  await refresh()
}

async function appeal(): Promise<void> {
  localNotice.value = ''
  const appealed = await grassland.appealDispute(props.disputeId, '对判决有异议')
  if (!appealed) return
  snapshot.value = appealed
  localNotice.value = '上诉已提交，等待客服终审'
}

async function doReauthenticate(): Promise<void> {
  localNotice.value = ''
  if (!reauthPassword.value) return
  const result = await grassland.reauthenticate(reauthPassword.value)
  reauthPassword.value = ''
  if (!result) return
  reauthAt.value = result.reauthenticatedAt ? new Date(result.reauthenticatedAt).toLocaleTimeString() : ''
  localNotice.value = `重认证成功（${result.authStrength}），5 分钟内可执行敏感操作`
}

async function submitFinalDecision(): Promise<void> {
  localNotice.value = ''
  const finalized = await grassland.finalDecision(props.disputeId, csDecision.value)
  if (!finalized) return
  snapshot.value = finalized
  localNotice.value = '终审已生效'
}
</script>

<template>
  <section class="adj">
    <header class="adj-head">
      <h4>审判看板</h4>
      <button type="button" class="adj-refresh" :disabled="grassland.loading.value" @click="refresh">刷新</button>
    </header>

    <p v-if="grassland.error.value" class="adj-alert adj-err" role="alert">{{ grassland.error.value }}</p>
    <p v-if="localNotice" class="adj-alert adj-ok">{{ localNotice }}</p>

    <div v-if="snapshot" class="adj-body">
      <!-- 状态概览 -->
      <dl class="adj-meta">
        <div><dt>状态</dt><dd>{{ statusLabel }}</dd></div>
        <div><dt>轮次</dt><dd>{{ snapshot.round || '未开始' }}</dd></div>
        <div><dt>面板</dt><dd>{{ snapshot.panel.size }} 人 / 已投 {{ snapshot.panel.voted }}</dd></div>
        <div><dt>判决</dt><dd>{{ decisionLabel(snapshot.decision) }}</dd></div>
        <div v-if="snapshot.finalDecision"><dt>终审</dt><dd>{{ decisionLabel(snapshot.finalDecision) }}</dd></div>
      </dl>

      <!-- 窗口倒计时：让用户知道当前阶段还要等多久（生产 24h/48h，dev 可配秒级） -->
      <div v-if="windowLabel && remaining !== null" class="adj-window">
        <span class="adj-window-label">{{ windowLabel }}</span>
        <span class="adj-window-time" :class="{ expired: remaining <= 0 }">{{ remainingText }}</span>
        <span v-if="snapshot.window.deadline" class="adj-hint">
          （截止 {{ new Date(snapshot.window.deadline).toLocaleString() }}）
        </span>
      </div>

      <!-- 计票条 -->
      <div v-if="tallies && tallies.panelSize > 0" class="adj-tally">
        <div class="adj-tally-row">
          <span class="adj-tally-label">商家方</span>
          <div class="adj-bar"><i class="adj-bar-fill adj-bar-m" :style="{ width: barWidth(tallies.forMerchant) }" /></div>
          <span class="adj-tally-num">{{ tallies.forMerchant }}</span>
        </div>
        <div class="adj-tally-row">
          <span class="adj-tally-label">推荐官方</span>
          <div class="adj-bar"><i class="adj-bar-fill adj-bar-r" :style="{ width: barWidth(tallies.forRecommender) }" /></div>
          <span class="adj-tally-num">{{ tallies.forRecommender }}</span>
        </div>
        <div class="adj-tally-row">
          <span class="adj-tally-label">弃权</span>
          <div class="adj-bar"><i class="adj-bar-fill adj-bar-a" :style="{ width: barWidth(tallies.abstain) }" /></div>
          <span class="adj-tally-num">{{ tallies.abstain }}</span>
        </div>
        <p class="adj-major">
          {{ tallies.majority ? `已过半：${decisionLabel(tallies.majority)}` : '尚无一方过半（平票将重开下一轮）' }}
        </p>
      </div>

      <!-- 启动审判（争议受理后尚未开庭） -->
      <div v-if="snapshot.status === 'open'" class="adj-act">
        <button type="button" :disabled="grassland.loading.value" @click="startAdjudication">启动审判</button>
        <span class="adj-hint">将随机抽取无利益冲突的审判官组成面板</span>
      </div>

      <!-- 审判官区 -->
      <div class="adj-act adj-judge">
        <template v-if="!isEnrolledJudge">
          <button type="button" :disabled="grassland.loading.value" @click="enroll">报名成为审判官</button>
          <span class="adj-hint">仅推荐官可报名；入池后可能被抽入面板</span>
        </template>
        <template v-else>
          <div v-if="isVoting" class="adj-vote">
            <input v-model="voteRationale" placeholder="投票理由（可选）" />
            <div class="adj-vote-btns">
              <button type="button" :disabled="grassland.loading.value" @click="vote('for_merchant')">支持商家</button>
              <button type="button" :disabled="grassland.loading.value" @click="vote('for_recommender')">支持推荐官</button>
              <button type="button" :disabled="grassland.loading.value" @click="vote('abstain')">弃权</button>
            </div>
            <span class="adj-hint">仅本轮面板成员可投；每官每轮一票，不可更改</span>
          </div>
          <div v-else class="adj-row">
            <span class="adj-hint">你在审判官池中（当前争议不在投票阶段）</span>
            <button type="button" class="adj-quiet" :disabled="grassland.loading.value" @click="leave">退出池</button>
          </div>
        </template>
      </div>

      <!-- 当事方上诉 -->
      <div v-if="isDecided" class="adj-act">
        <button type="button" :disabled="grassland.loading.value" @click="appeal">对判决提起上诉</button>
        <span class="adj-hint">仅判决后的上诉窗口内可提起，每争议一次</span>
      </div>

      <!-- 客服终审 -->
      <div v-if="!isFinal" class="adj-act adj-cs">
        <details>
          <summary>客服终审（覆盖判决）</summary>
          <div class="adj-row">
            <input v-model="reauthPassword" type="password" placeholder="密码（重认证）" />
            <button type="button" :disabled="grassland.loading.value" @click="doReauthenticate">重认证</button>
            <span v-if="reauthAt" class="adj-hint">已重认证 {{ reauthAt }}</span>
          </div>
          <div class="adj-row">
            <select v-model="csDecision">
              <option value="for_merchant">判商家方胜诉</option>
              <option value="for_recommender">判推荐官方胜诉</option>
            </select>
            <button type="button" :disabled="grassland.loading.value" @click="submitFinalDecision">提交终审</button>
          </div>
          <p class="adj-hint">
            需账号角色为客服（或管理员）+ 5 分钟内完成过重认证。终审可<strong>覆盖</strong>面板判决。
          </p>
        </details>
      </div>
    </div>

    <p v-else class="adj-hint">加载中…</p>
  </section>
</template>

<style scoped>
.adj { border: 1px solid var(--color-border); border-radius: 10px; padding: 14px; display: flex; flex-direction: column; gap: 12px; }
.adj-head { display: flex; justify-content: space-between; align-items: center; }
.adj-head h4 { margin: 0; font-size: 15px; }
.adj-refresh { font-size: 12px; padding: 3px 10px; }
.adj-alert { margin: 0; padding: 7px 11px; border-radius: 6px; font-size: 13px; }
.adj-err { background: color-mix(in srgb, var(--color-danger) 14%, transparent); color: var(--color-danger); }
.adj-ok { background: color-mix(in srgb, var(--color-success) 14%, transparent); color: var(--color-success); }
.adj-body { display: flex; flex-direction: column; gap: 14px; }
.adj-meta { display: grid; grid-template-columns: repeat(auto-fit, minmax(130px, 1fr)); gap: 10px; margin: 0; }
.adj-meta div { display: flex; flex-direction: column; gap: 2px; }
.adj-meta dt { font-size: 11px; opacity: 0.6; }
.adj-meta dd { margin: 0; font-size: 13px; font-weight: 500; }
.adj-window {
  display: flex; align-items: center; gap: 8px; flex-wrap: wrap;
  padding: 7px 11px; border-radius: 6px; background: var(--color-surface-strong); font-size: 13px;
}
.adj-window-label { font-weight: 500; }
.adj-window-time { font-variant-numeric: tabular-nums; }
.adj-window-time.expired { color: var(--color-accent-warm); }
.adj-tally { display: flex; flex-direction: column; gap: 6px; }
.adj-tally-row { display: flex; align-items: center; gap: 8px; }
.adj-tally-label { flex: 0 0 62px; font-size: 12px; opacity: 0.75; }
.adj-bar { flex: 1; height: 9px; background: var(--color-surface-strong); border-radius: 5px; overflow: hidden; }
.adj-bar-fill { display: block; height: 100%; border-radius: 5px; transition: width 0.3s ease; }
.adj-bar-m { background: var(--color-accent); }
.adj-bar-r { background: var(--color-success); }
.adj-bar-a { background: var(--color-text-muted); }
.adj-tally-num { flex: 0 0 20px; text-align: right; font-size: 12px; font-variant-numeric: tabular-nums; }
.adj-major { margin: 2px 0 0; font-size: 12px; opacity: 0.7; }
.adj-act { display: flex; flex-direction: column; gap: 6px; padding-top: 10px; border-top: 1px solid var(--color-border); }
.adj-row { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.adj-vote { display: flex; flex-direction: column; gap: 8px; }
.adj-vote input { padding: 6px 10px; border: 1px solid var(--color-border); background: var(--color-surface); color: var(--color-text); border-radius: 6px; font-size: 13px; }
.adj-vote-btns { display: flex; gap: 8px; flex-wrap: wrap; }
button { padding: 6px 14px; border: 1px solid var(--color-border); background: transparent; color: var(--color-text); border-radius: 6px; cursor: pointer; font-size: 13px; }
button:hover:not(:disabled) { border-color: var(--color-border-hover); background: var(--color-surface-hover); }
button:disabled { opacity: 0.5; cursor: not-allowed; }
.adj-quiet { opacity: 0.7; font-size: 12px; }
select { padding: 6px 10px; border: 1px solid var(--color-border); background: var(--color-surface); color: var(--color-text); border-radius: 6px; font-size: 13px; }
.adj-hint { margin: 0; font-size: 12px; opacity: 0.62; }
.adj-cs summary { font-size: 13px; cursor: pointer; }
.adj-warn { margin: 8px 0 0; font-size: 12px; color: var(--color-accent-warm); background: color-mix(in srgb, var(--color-accent-warm) 12%, transparent); padding: 7px 10px; border-radius: 6px; }
</style>
