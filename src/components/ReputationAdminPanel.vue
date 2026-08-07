<template>
  <section class="reputation-admin" aria-label="等级与权益治理">
    <div class="panel-toolbar">
      <div>
        <h3>等级策略</h3>
        <p v-if="policy">策略版本 {{ policy.version }} · {{ formatDateTime(policy.updatedAt) }}</p>
      </div>
      <div class="toolbar-actions">
        <button type="button" class="secondary-btn" :disabled="policyLoading" @click="loadPolicy">
          刷新
        </button>
        <button
          type="button"
          class="primary-btn"
          data-testid="save-reputation-policy"
          :disabled="policyLoading || savingPolicy || !policy"
          @click="savePolicy"
        >
          {{ savingPolicy ? '保存中...' : '保存策略' }}
        </button>
      </div>
    </div>

    <p v-if="policyError" class="error-msg" role="alert">{{ policyError }}</p>
    <p v-if="policyNotice" class="success-msg" role="status">{{ policyNotice }}</p>
    <div v-if="policyLoading" class="loading-state">加载中...</div>

    <fieldset v-else class="level-list level-fieldset" :disabled="savingPolicy">
      <article v-for="(level, index) in levels" :key="level.level" class="reputation-level-row">
        <header class="level-heading">
          <strong>{{ level.level }}</strong>
          <input
            :value="level.title"
            type="text"
            maxlength="32"
            :aria-label="`${level.level} 名称`"
            @input="patchLevel(index, { title: textValue($event) })"
          />
          <span v-if="level.inviteOnly" class="status-tag status-invite">邀请制</span>
          <span v-if="level.judgeEligible" class="status-tag status-judge">审判资格</span>
        </header>

        <div class="level-fields">
          <label>完成任务
            <input
              :value="level.minCompleted"
              type="number"
              min="0"
              max="1000000"
              :disabled="level.levelNumber === 1"
              :data-testid="`level-${level.levelNumber}-min-completed`"
              @input="patchNumber(index, 'minCompleted', $event)"
            />
          </label>
          <label>完成率
            <span class="input-suffix">
              <input
                :value="ratePercent(level.minCompletionRate)"
                type="number"
                min="0"
                max="100"
                step="1"
                :disabled="level.levelNumber === 1"
                @input="patchRate(index, $event)"
              />
              <span>%</span>
            </span>
          </label>
          <label>最低评分
            <input
              :value="level.minAverageScore ?? ''"
              type="number"
              min="0"
              max="5"
              step="0.1"
              :disabled="level.levelNumber === 1"
              placeholder="不限"
              @input="patchNullableNumber(index, 'minAverageScore', $event)"
            />
          </label>
          <label>排序权重
            <input
              :value="level.taskPriorityWeight"
              type="number"
              min="1"
              max="10000"
              @input="patchNumber(index, 'taskPriorityWeight', $event)"
            />
          </label>
          <label>结算天数
            <input
              :value="level.settlementDelayDays"
              type="number"
              min="0"
              max="30"
              @input="patchNumber(index, 'settlementDelayDays', $event)"
            />
          </label>
          <label>佣金加成
            <span class="input-suffix">
              <input
                :value="basisPointsPercent(level.commissionBonusBps)"
                type="number"
                min="0"
                max="100"
                step="0.1"
                @input="patchBasisPoints(index, 'commissionBonusBps', $event)"
              />
              <span>%</span>
            </span>
          </label>
          <label>AI 额度倍率
            <span class="input-suffix">
              <input
                :value="basisPointsMultiplier(level.aiQuotaMultiplierBps)"
                type="number"
                min="0.1"
                max="10"
                step="0.1"
                @input="patchMultiplier(index, $event)"
              />
              <span>×</span>
            </span>
          </label>
          <label class="check-field">
            <input
              :checked="level.premiumSupport"
              type="checkbox"
              @change="patchLevel(index, { premiumSupport: checkedValue($event) })"
            />
            专属支持
          </label>
        </div>

        <label class="benefits-field">权益列表
          <textarea
            :value="level.benefits.join('\n')"
            rows="2"
            maxlength="2064"
            placeholder="每行一项权益"
            @input="patchBenefits(index, $event)"
          />
        </label>
      </article>
    </fieldset>

    <section class="account-section" aria-labelledby="lv5-admission-title">
      <div class="panel-toolbar compact">
        <div>
          <h3 id="lv5-admission-title">Lv5 邀请</h3>
          <p>按账号查询实时履约指标与有效等级</p>
        </div>
      </div>
      <div class="account-search">
        <label>账号 ID
          <input
            v-model.trim="accountId"
            data-testid="reputation-account-id"
            type="text"
            autocomplete="off"
            :disabled="admissionSaving"
            placeholder="推荐官账号 UUID"
            @keyup.enter="loadAccountReputation"
          />
        </label>
        <button
          type="button"
          class="secondary-btn"
          data-testid="load-admin-reputation"
          :disabled="accountLoading || admissionSaving"
          @click="loadAccountReputation"
        >查询</button>
      </div>
      <p v-if="accountError" class="error-msg" role="alert">{{ accountError }}</p>
      <p v-if="accountNotice" class="success-msg" role="status">{{ accountNotice }}</p>

      <div v-if="reputation" class="reputation-detail">
        <div class="detail-summary">
          <div><span>账号</span><strong class="account-id-value">{{ reputation.accountId }}</strong></div>
          <div><span>计算等级</span><strong>{{ reputation.calculatedLevel }} · {{ reputation.levelTitle }}</strong></div>
          <div><span>有效等级</span><strong>当前生效 {{ reputation.effectiveLevel }}</strong></div>
          <div><span>完成任务</span><strong>{{ reputation.completedCount }}</strong></div>
          <div><span>完成率</span><strong>{{ ratePercent(reputation.completionRate) }}%</strong></div>
          <div><span>平均评分</span><strong>{{ reputation.averageScore ?? '暂无' }}</strong></div>
          <div><span>邀请状态</span><strong>{{ reputation.lv5Admitted ? '已授予' : '未授予' }}</strong></div>
        </div>
        <p v-if="reputation.calculatedLevel === 'Lv5'" class="eligibility-ok">指标已达 Lv5</p>
        <p v-else class="eligibility-warn">指标尚未达到 Lv5</p>
        <label class="admission-note">操作备注
          <textarea
            v-model="admissionNote"
            data-testid="lv5-admission-note"
            rows="2"
            maxlength="500"
            :disabled="admissionSaving"
            placeholder="填写授予或撤销原因"
          />
        </label>
        <div class="admission-actions">
          <button
            v-if="!reputation.lv5Admitted"
            type="button"
            class="primary-btn"
            data-testid="grant-lv5"
            :disabled="admissionSaving"
            @click="changeLv5Admission(true)"
          >授予 Lv5</button>
          <button
            v-else
            type="button"
            class="danger-btn"
            data-testid="revoke-lv5"
            :disabled="admissionSaving"
            @click="changeLv5Admission(false)"
          >撤销 Lv5</button>
        </div>
      </div>
    </section>
  </section>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useGrassland } from '../composables/useGrassland'
import type {
  AdminReputation,
  ReputationLevelRule,
  ReputationPolicy,
} from '../types/grassland'

type NumericRuleKey =
  | 'minCompleted'
  | 'taskPriorityWeight'
  | 'settlementDelayDays'
  | 'commissionBonusBps'

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const grassland = useGrassland()
const policy = ref<ReputationPolicy | null>(null)
const levels = ref<ReputationLevelRule[]>([])
const policyLoading = ref(false)
const savingPolicy = ref(false)
const policyError = ref('')
const policyNotice = ref('')

const accountId = ref('')
const reputation = ref<AdminReputation | null>(null)
const accountLoading = ref(false)
const admissionSaving = ref(false)
const admissionNote = ref('')
const accountError = ref('')
const accountNotice = ref('')
let accountRequestSequence = 0

function cloneLevels(values: ReputationLevelRule[]): ReputationLevelRule[] {
  return values.map((level) => ({ ...level, benefits: [...level.benefits] }))
}

function applyPolicy(value: ReputationPolicy): void {
  policy.value = { ...value, levels: cloneLevels(value.levels) }
  levels.value = cloneLevels(value.levels)
}

async function loadPolicy(): Promise<void> {
  policyLoading.value = true
  policyError.value = ''
  policyNotice.value = ''
  const result = await grassland.getReputationPolicy()
  if (result) applyPolicy(result)
  else policyError.value = grassland.error.value || '等级策略加载失败'
  policyLoading.value = false
}

async function savePolicy(): Promise<void> {
  if (savingPolicy.value) return
  if (!policy.value) return
  if (levels.value.some((level) => !level.title.trim() || !validNumbers(level))) {
    policyError.value = '请完整填写合法的等级参数'
    return
  }
  const normalizedLevels = levels.value.map((level) => ({
    ...level,
    title: level.title.trim(),
    benefits: level.benefits.map((item) => item.trim()).filter(Boolean),
  }))
  savingPolicy.value = true
  policyError.value = ''
  policyNotice.value = ''
  const result = await grassland.updateReputationPolicy({
    expectedVersion: policy.value.version,
    levels: cloneLevels(normalizedLevels),
  })
  if (result) {
    applyPolicy(result)
    policyNotice.value = '等级策略已保存'
  } else {
    policyError.value = grassland.error.value || '等级策略保存失败'
  }
  savingPolicy.value = false
}

function patchLevel(index: number, patch: Partial<ReputationLevelRule>): void {
  levels.value = levels.value.map((level, current) => current === index
    ? { ...level, ...patch, benefits: patch.benefits ? [...patch.benefits] : [...level.benefits] }
    : level)
}

function textValue(event: Event): string {
  return (event.currentTarget as HTMLInputElement | HTMLTextAreaElement).value
}

function checkedValue(event: Event): boolean {
  return (event.currentTarget as HTMLInputElement).checked
}

function patchNumber(index: number, key: NumericRuleKey, event: Event): void {
  patchLevel(index, { [key]: Number(textValue(event)) })
}

function patchNullableNumber(index: number, key: 'minAverageScore', event: Event): void {
  const value = textValue(event)
  patchLevel(index, { [key]: value === '' ? null : Number(value) })
}

function patchRate(index: number, event: Event): void {
  patchLevel(index, { minCompletionRate: Number(textValue(event)) / 100 })
}

function patchBasisPoints(index: number, key: 'commissionBonusBps', event: Event): void {
  patchLevel(index, { [key]: Math.round(Number(textValue(event)) * 100) })
}

function patchMultiplier(index: number, event: Event): void {
  patchLevel(index, { aiQuotaMultiplierBps: Math.round(Number(textValue(event)) * 10000) })
}

function patchBenefits(index: number, event: Event): void {
  const benefits = textValue(event).split('\n')
  patchLevel(index, { benefits })
}

function validNumbers(level: ReputationLevelRule): boolean {
  return [
    level.minCompleted,
    level.minCompletionRate,
    level.minAverageScore ?? 0,
    level.taskPriorityWeight,
    level.settlementDelayDays,
    level.commissionBonusBps,
    level.aiQuotaMultiplierBps,
  ].every(Number.isFinite)
}

async function loadAccountReputation(): Promise<void> {
  const normalized = accountId.value.trim()
  accountError.value = ''
  accountNotice.value = ''
  reputation.value = null
  if (!UUID_PATTERN.test(normalized)) {
    accountError.value = '请输入合法的账号 UUID'
    return
  }
  const requestSequence = ++accountRequestSequence
  accountLoading.value = true
  const result = await grassland.getAdminReputation(normalized)
  if (requestSequence !== accountRequestSequence) return
  if (result) {
    reputation.value = { ...result, benefits: [...result.benefits] }
    admissionNote.value = result.admissionNote || ''
  } else {
    accountError.value = grassland.error.value || '账号声誉加载失败'
  }
  accountLoading.value = false
}

async function changeLv5Admission(admitted: boolean): Promise<void> {
  if (admissionSaving.value) return
  const current = reputation.value
  const note = admissionNote.value.trim()
  if (!current) return
  if (!note) {
    accountError.value = '请填写 Lv5 操作备注'
    return
  }
  admissionSaving.value = true
  accountError.value = ''
  accountNotice.value = ''
  const changed = await grassland.updateLv5Admission(current.accountId, {
    admitted,
    expectedVersion: current.admissionVersion,
    note,
  })
  if (!changed) {
    accountError.value = grassland.error.value || 'Lv5 邀请状态更新失败'
    admissionSaving.value = false
    return
  }
  const refreshed = await grassland.getAdminReputation(current.accountId)
  if (refreshed) {
    reputation.value = { ...refreshed, benefits: [...refreshed.benefits] }
    admissionNote.value = ''
    accountNotice.value = admitted ? 'Lv5 已授予' : 'Lv5 已撤销'
  } else {
    accountError.value = grassland.error.value || '状态已更新，请刷新账号声誉'
  }
  admissionSaving.value = false
}

function ratePercent(value: number): number {
  return Math.round(value * 10000) / 100
}

function basisPointsPercent(value: number): number {
  return Math.round(value) / 100
}

function basisPointsMultiplier(value: number): number {
  return Math.round(value) / 10000
}

function formatDateTime(value: string | null): string {
  if (!value) return '尚未更新'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '时间未知' : date.toLocaleString('zh-CN')
}

onMounted(() => void loadPolicy())
</script>

<style scoped>
.reputation-admin { display: grid; gap: 18px; }
.level-fieldset { min-width: 0; margin: 0; padding: 0; border: 0; }
.panel-toolbar { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; }
.panel-toolbar h3 { margin: 0; font-size: 17px; }
.panel-toolbar p { margin: 5px 0 0; color: var(--color-text-muted); font-size: 13px; }
.panel-toolbar.compact { margin-bottom: 12px; }
.toolbar-actions, .admission-actions { display: flex; gap: 8px; }
.primary-btn, .secondary-btn, .danger-btn {
  min-height: 36px; padding: 0 14px; border-radius: 6px; border: 1px solid transparent;
  font: inherit; font-weight: 600; cursor: pointer;
}
.primary-btn { background: #2563eb; color: #fff; }
.secondary-btn { background: var(--color-surface); border-color: var(--color-border); color: var(--color-text); }
.danger-btn { background: #b42318; color: #fff; }
button:disabled { opacity: .55; cursor: not-allowed; }
.error-msg, .success-msg { margin: 0; padding: 9px 11px; border-radius: 6px; font-size: 13px; }
.error-msg { background: #fef3f2; color: #b42318; border: 1px solid #fecdca; }
.success-msg { background: #ecfdf3; color: #067647; border: 1px solid #abefc6; }
.loading-state { padding: 24px; text-align: center; color: var(--color-text-muted); }
.level-list { display: grid; gap: 10px; }
.reputation-level-row { padding: 14px; border: 1px solid var(--color-border); border-radius: 6px; background: var(--color-surface); }
.level-heading { display: grid; grid-template-columns: 48px minmax(150px, 240px) auto auto; align-items: center; gap: 8px; }
.level-heading strong { font-size: 16px; }
.level-heading input, .level-fields input, textarea, .account-search input {
  width: 100%; box-sizing: border-box; border: 1px solid var(--color-border); border-radius: 5px;
  background: var(--color-surface); color: var(--color-text); font: inherit;
}
.level-heading input, .level-fields input, .account-search input { height: 34px; padding: 0 9px; }
textarea { padding: 8px 9px; resize: vertical; }
.status-tag { justify-self: start; padding: 3px 7px; border-radius: 999px; font-size: 11px; font-weight: 700; }
.status-invite { color: #9a3412; background: #fff7ed; }
.status-judge { color: #166534; background: #f0fdf4; }
.level-fields { display: grid; grid-template-columns: repeat(4, minmax(120px, 1fr)); gap: 10px; margin-top: 12px; }
.level-fields label, .benefits-field, .account-search label, .admission-note { display: grid; gap: 5px; color: var(--color-text-muted); font-size: 12px; }
.input-suffix { display: grid; grid-template-columns: minmax(0, 1fr) 24px; align-items: center; }
.input-suffix input { border-radius: 5px 0 0 5px; }
.input-suffix span { height: 32px; display: grid; place-items: center; border: 1px solid var(--color-border); border-left: 0; border-radius: 0 5px 5px 0; }
.check-field { display: flex !important; align-items: center; gap: 7px !important; padding-top: 22px; }
.check-field input { width: 16px; height: 16px; }
.benefits-field { margin-top: 10px; }
.account-section { border-top: 1px solid var(--color-border); padding-top: 18px; }
.account-search { display: grid; grid-template-columns: minmax(260px, 520px) auto; align-items: end; gap: 8px; }
.reputation-detail { margin-top: 14px; display: grid; gap: 12px; }
.detail-summary { display: grid; grid-template-columns: repeat(3, minmax(130px, 1fr)); gap: 1px; background: var(--color-border); border: 1px solid var(--color-border); border-radius: 6px; overflow: hidden; }
.detail-summary div { display: grid; gap: 4px; padding: 12px; background: var(--color-surface); }
.detail-summary span { color: var(--color-text-muted); font-size: 12px; }
.account-id-value { overflow-wrap: anywhere; font-family: ui-monospace, monospace; font-size: 12px; }
.eligibility-ok, .eligibility-warn { margin: 0; font-size: 13px; font-weight: 600; }
.eligibility-ok { color: #067647; }
.eligibility-warn { color: #b54708; }
.admission-note { max-width: 620px; }
@media (max-width: 880px) {
  .level-fields { grid-template-columns: repeat(2, minmax(120px, 1fr)); }
  .detail-summary { grid-template-columns: repeat(2, minmax(120px, 1fr)); }
}
@media (max-width: 560px) {
  .panel-toolbar { flex-direction: column; }
  .level-heading { grid-template-columns: 42px minmax(0, 1fr); }
  .level-fields, .detail-summary, .account-search { grid-template-columns: 1fr; }
}
</style>
