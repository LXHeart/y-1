<template>
  <article class="gl-card gl-card-wide">
    <h3>3. 发布任务<span v-if="revisingTask" class="gl-hint"> · 正在修订已发布任务（保存出新版本）</span><span v-else-if="editingDraft" class="gl-hint"> · 正在编辑草稿（保存后仍为草稿，需在上方「提交审核」）</span><span v-else class="gl-hint"> · 提交后经平台内容审核，通过后在大厅上架</span></h3>
    <div class="gl-row">
      <label>资源范围
        <select name="task-scope" :value="selectedStoreId" :disabled="Boolean(editingDraft || revisingTask)" @change="$emit('change-store', ($event.target as HTMLSelectElement).value)">
          <option v-if="hasOrganizationAccess" value="">组织级任务</option>
          <option v-for="store in stores" :key="store.id" :value="store.id">门店：{{ store.name }}</option>
        </select>
      </label>
      <input :value="form.title" aria-label="任务标题" name="task-title" autocomplete="off" placeholder="任务标题" @input="updateField('title', ($event.target as HTMLInputElement).value)" />
      <input :value="form.platform" aria-label="平台（可选）" name="task-platform" autocomplete="off" placeholder="平台（可选）" @input="updateField('platform', ($event.target as HTMLInputElement).value)" />
      <label>内容形式
        <select name="task-content-form" :value="form.contentForm" @change="updateField('contentForm', ($event.target as HTMLSelectElement).value)">
          <option value="">未指定</option>
          <option value="image">图文种草</option>
          <option value="video">视频种草</option>
          <option value="article">文章</option>
          <option value="interaction">互动</option>
        </select>
      </label>
    </div>
    <div v-if="interactionForm" class="gl-row">
      <input :value="form.interactionTargetUrl" type="url" inputmode="url" spellcheck="false" aria-label="互动目标链接" name="interaction-target-url" autocomplete="off" placeholder="互动目标链接（https://…，必填）" @input="updateField('interactionTargetUrl', ($event.target as HTMLInputElement).value)" />
      <label>动作类型
        <select name="interaction-action-type" :value="form.interactionActionType" @change="updateField('interactionActionType', ($event.target as HTMLSelectElement).value)">
          <option value="like">点赞</option>
          <option value="favorite">收藏</option>
          <option value="follow">关注</option>
          <option value="comment">评论</option>
        </select>
      </label>
    </div>
    <div class="gl-row">
      <input :value="form.description" aria-label="任务描述（可选）" name="task-description" autocomplete="off" placeholder="任务描述（可选）" @input="updateField('description', ($event.target as HTMLInputElement).value)" />
      <textarea :value="form.productServiceInfo" aria-label="产品服务信息" name="task-product-service" autocomplete="off" placeholder="产品/服务信息" rows="3" @input="updateField('productServiceInfo', ($event.target as HTMLTextAreaElement).value)" />
    </div>
    <div class="gl-row task-requirement-grid">
      <label>必须包含
        <textarea :value="form.mustInclude" aria-label="必须包含" name="task-must-include" autocomplete="off" rows="4" @input="updateField('mustInclude', ($event.target as HTMLTextAreaElement).value)" />
      </label>
      <label>禁止内容
        <textarea :value="form.forbiddenContent" aria-label="禁止内容" name="task-forbidden-content" autocomplete="off" rows="4" @input="updateField('forbiddenContent', ($event.target as HTMLTextAreaElement).value)" />
      </label>
      <label>指标要求
        <textarea :value="form.metricRequirements" aria-label="指标要求" name="task-metric-requirements" autocomplete="off" rows="4" @input="updateField('metricRequirements', ($event.target as HTMLTextAreaElement).value)" />
      </label>
      <label>凭证要求
        <textarea :value="form.evidenceRequirements" aria-label="凭证要求" name="task-evidence-requirements" autocomplete="off" rows="4" @input="updateField('evidenceRequirements', ($event.target as HTMLTextAreaElement).value)" />
      </label>
    </div>
    <div class="gl-row">
      <label>最早发布时间 <input :value="form.publishStartAt" name="task-publish-start" autocomplete="off" type="datetime-local" @input="updateField('publishStartAt', ($event.target as HTMLInputElement).value)" /></label>
      <label>最晚发布时间 <input :value="form.publishEndAt" name="task-publish-end" autocomplete="off" type="datetime-local" @input="updateField('publishEndAt', ($event.target as HTMLInputElement).value)" /></label>
    </div>
    <div class="gl-row">
      <label>名额 <input :value="form.maxSlots" name="task-max-slots" autocomplete="off" type="number" min="1" @input="updateField('maxSlots', Number(($event.target as HTMLInputElement).value))" /></label>
      <label>赏金 ¥<input :value="form.bountyYuan" name="task-bounty" autocomplete="off" type="number" min="0" :disabled="!canPublishBounty" @input="updateField('bountyYuan', Number(($event.target as HTMLInputElement).value))" /></label>
      <label>霸王餐押金 ¥<input :value="form.freebieDepositYuan" name="task-freebie-deposit" autocomplete="off" type="number" min="0" :disabled="ladderEnabled || !canPublishBounty" @input="updateField('freebieDepositYuan', Number(($event.target as HTMLInputElement).value))" /></label>
      <label>报名截止 <input :value="form.applicationDeadline" name="task-deadline" autocomplete="off" type="datetime-local" @input="updateField('applicationDeadline', ($event.target as HTMLInputElement).value)" /></label>
      <label>最低等级
        <select name="task-min-level" :value="form.minRecommenderLevel" @change="updateField('minRecommenderLevel', Number(($event.target as HTMLSelectElement).value))">
          <option v-for="level in 5" :key="level" :value="level">Lv{{ level }}</option>
        </select>
      </label>
      <label>自动通过
        <select name="task-auto-accept" :value="form.autoAcceptMinLevel" @change="updateField('autoAcceptMinLevel', ($event.target as HTMLSelectElement).value === '' ? null : Number(($event.target as HTMLSelectElement).value))">
          <option value="">关闭</option>
          <option v-for="level in 5" :key="level" :value="level">Lv{{ level }}+</option>
        </select>
      </label>
    </div>
    <p v-if="bountyActive || freebieActive" class="gl-hint">
      {{ fundingHint }}
    </p>
    <div class="gl-row commission-ladder-toggle-row">
      <label>阶梯佣金
        <input type="checkbox" aria-label="启用阶梯佣金" name="commission-ladder-enabled" :checked="ladderForm.enabled" :disabled="freebieActive" @change="patchCommissionLadder({ enabled: ($event.target as HTMLInputElement).checked })" />
      </label>
      <span v-if="freebieActive" class="gl-hint">霸王餐押金启用中，不能同时开启阶梯佣金</span>
    </div>
    <div v-if="ladderForm.enabled" class="commission-ladder-editor">
      <label>指标标识
        <input :value="ladderForm.metricKey" aria-label="阶梯佣金指标标识" name="commission-metric-key" autocomplete="off" spellcheck="false" placeholder="如 douyin.play_count（字母开头，可用字母/数字/点/下划线/连字符）" @input="patchCommissionLadder({ metricKey: ($event.target as HTMLInputElement).value })" />
      </label>
      <ul class="gl-list commission-tier-list">
        <li v-for="(tier, index) in ladderForm.tiers" :key="index" class="gl-row commission-tier-row">
          <label>第 {{ index + 1 }} 档阈值
            <input :value="tier.threshold" type="number" min="0" :name="`commission-tier-${index + 1}-threshold`" autocomplete="off" :aria-label="`第 ${index + 1} 档阈值`" @input="patchCommissionTier(index, { threshold: Number(($event.target as HTMLInputElement).value) })" />
          </label>
          <label>第 {{ index + 1 }} 档佣金 ¥
            <input :value="tier.payoutYuan" type="number" min="0" step="0.01" :name="`commission-tier-${index + 1}-payout`" autocomplete="off" :aria-label="`第 ${index + 1} 档佣金`" @input="patchCommissionTier(index, { payoutYuan: Number(($event.target as HTMLInputElement).value) })" />
          </label>
          <button type="button" :aria-label="`删除第 ${index + 1} 档`" :disabled="ladderForm.tiers.length <= 1" @click="removeCommissionTier(index)">删除档位</button>
        </li>
      </ul>
      <button v-if="ladderForm.tiers.length < 20" type="button" aria-label="添加档位" @click="addCommissionTier()">添加档位</button>
      <p class="gl-hint">按已达最高档结算：达到最高档只发该档固定佣金、不累加；最高档佣金由任务赏金足额预留。最多 20 档，阈值与金额在提交时统一校验。</p>
    </div>
    <div class="gl-row">
      <button v-if="!revisingTask" type="button" :disabled="!activeOrgId || loading" @click="$emit('publish')">提交审核</button>
      <button type="button" :disabled="!activeOrgId || loading" @click="$emit('save-draft')">{{ revisingTask ? '保存修订' : (editingDraft ? '保存草稿' : '存为草稿') }}</button>
      <button v-if="editingDraft || revisingTask" type="button" :disabled="loading" @click="confirmResetForm">取消编辑</button>
    </div>
    <p class="gl-hint">赏金 &gt; 0 的任务为资金型：接受报名时会走资金预留 Saga（异步）。「自动通过」开启后对存量待处理报名生效；资金不足或名额满时回退人工处理。草稿不占发布额度、不需资金权限。已发布任务可「编辑」出新版本；改赏金/平台<b>只影响新报名</b>，已接受的履约按其接受时的金额结算（snapshot-pinning）。霸王餐押金可与赏金<b>组合</b>（任务书 #46）：押金由推荐官报名被接受时从钱包预付进平台托管，达标（核实+确认）全额返还推荐官，未达标补偿商家；赏金腿照常由商家出资、结算付推荐官。阶梯佣金仍不可与押金同设。</p>
  </article>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, watch } from 'vue'
import type { Store } from '../../../types/grassland'
import { formatYuan, yuanToCents } from '../../../lib/money'
import { emptyCommissionLadderForm } from './commission-ladder'
import type { CommissionLadderFormData, CommissionLadderFormTier } from './commission-ladder'

interface TaskFormData {
  title: string
  description: string
  platform: string
  contentForm: string
  /** 任务书 #23：互动任务条件字段（contentForm=interaction 时展示、必填）。 */
  interactionTargetUrl: string
  interactionActionType: string
  maxSlots: number
  bountyYuan: number
  freebieDepositYuan: number
  applicationDeadline: string
  minRecommenderLevel: number
  autoAcceptMinLevel: number | null
  productServiceInfo: string
  mustInclude: string
  forbiddenContent: string
  publishStartAt: string
  publishEndAt: string
  metricRequirements: string
  evidenceRequirements: string
  /** 任务书 #25：阶梯佣金表单元数据（可选——父组件未接入时按默认关闭渲染）。 */
  commissionLadder?: CommissionLadderFormData
}

const props = defineProps<{
  form: TaskFormData
  editingDraft: { id: string; version: number } | null
  revisingTask: { id: string; version: number } | null
  stores: Store[]
  selectedStoreId: string
  activeOrgId: string
  hasOrganizationAccess: boolean
  canPublishBounty: boolean
  loading: boolean
}>()

/** 任务书 #46：赏金与押金可组合（两腿独立）；仍互斥的是阶梯 × 押金（#25）。 */
/** 任务书 #23 R6：contentForm=interaction 时展示目标链接 + 动作类型两个必填字段。 */
/** 任务书 #25：阶梯佣金（赏金模式）与霸王餐押金互斥——freebie>0 禁用阶梯开关，阶梯启用禁用押金输入。 */
const interactionForm = computed(() => props.form.contentForm === 'interaction')
const bountyActive = computed(() => props.form.bountyYuan > 0)
const freebieActive = computed(() => props.form.freebieDepositYuan > 0)
/** 阶梯表单元数据；父组件（Task 3）未接入时回退默认关闭表单，保证旧挂载点不受影响。 */
const ladderForm = computed<CommissionLadderFormData>(
  () => props.form.commissionLadder ?? emptyCommissionLadderForm(),
)
const ladderEnabled = computed(() => ladderForm.value.enabled)
const fundingHint = computed(() => {
  if (bountyActive.value && freebieActive.value) {
    return `组合模式：赏金 ${formatYuan(yuanToCents(props.form.bountyYuan))} 由商家出资托管，押金 ${formatYuan(yuanToCents(props.form.freebieDepositYuan))} 由推荐官预付、达标全额返还（两腿独立结算）`
  }
  return freebieActive.value
    ? `霸王餐押金模式：推荐官报名被接受时从钱包预付 ${formatYuan(yuanToCents(props.form.freebieDepositYuan))}，达标全额返还`
    : '赏金模式：商家出资托管，结算时打给推荐官；可与霸王餐押金组合（押金退推荐官、赏金付推荐官）'
})

const emit = defineEmits<{
  'update:field': [field: string, value: string | number | null]
  'update:commission-ladder': [value: CommissionLadderFormData]
  'change-store': [storeId: string]
  publish: []
  'save-draft': []
  'reset-form': []
}>()

// ---------- 未保存变更守卫（Web Interface Guidelines：带未保存修改离开需警告） ----------
// SPA 内切标签页由 KeepAlive 保活、状态不丢，守卫只针对整页刷新/关闭（beforeunload）。
// 基线随编辑上下文重置：进入/退出草稿·修订时父组件整体换表单值，那一刻的差异不是用户输入。
let formBaseline = ''
const recordFormBaseline = (): void => { formBaseline = JSON.stringify(props.form) }
const formDirty = computed(() => JSON.stringify(props.form) !== formBaseline)

function warnUnsavedChanges(event: BeforeUnloadEvent): void {
  event.preventDefault()
  event.returnValue = ''
}

onMounted(recordFormBaseline)
watch(() => [props.editingDraft, props.revisingTask], recordFormBaseline)
watch(formDirty, (dirty) => {
  if (dirty) window.addEventListener('beforeunload', warnUnsavedChanges)
  else window.removeEventListener('beforeunload', warnUnsavedChanges)
})
onBeforeUnmount(() => window.removeEventListener('beforeunload', warnUnsavedChanges))

/** 丢弃未保存修改属破坏性操作：脏表单先经确认（未改动则直接取消，不加摩擦）。 */
function confirmResetForm(): void {
  if (formDirty.value && !window.confirm('取消编辑将丢弃当前未保存的修改，确定继续？')) return
  emit('reset-form')
}

function updateField(field: string, value: string | number | null): void {
  emit('update:field', field, value)
}

/** 阶梯佣金整值事件（任务书 #25）：所有变更以不可变更新发出完整 CommissionLadderFormData。 */

function emitCommissionLadder(value: CommissionLadderFormData): void {
  emit('update:commission-ladder', value)
}

function patchCommissionLadder(patch: Partial<CommissionLadderFormData>): void {
  emitCommissionLadder({ ...ladderForm.value, ...patch })
}

function patchCommissionTier(index: number, patch: Partial<CommissionLadderFormTier>): void {
  emitCommissionLadder({
    ...ladderForm.value,
    tiers: ladderForm.value.tiers.map((tier, tierIndex) =>
      tierIndex === index ? { ...tier, ...patch } : tier),
  })
}

function addCommissionTier(): void {
  if (ladderForm.value.tiers.length >= 20) return
  const last = ladderForm.value.tiers[ladderForm.value.tiers.length - 1]
  emitCommissionLadder({
    ...ladderForm.value,
    tiers: [...ladderForm.value.tiers, {
      threshold: (last?.threshold ?? 0) + 1,
      payoutYuan: last?.payoutYuan ?? 0,
    }],
  })
}

function removeCommissionTier(index: number): void {
  if (ladderForm.value.tiers.length <= 1) return
  emitCommissionLadder({
    ...ladderForm.value,
    tiers: ladderForm.value.tiers.filter((_, tierIndex) => tierIndex !== index),
  })
}
</script>

<style scoped>
.task-requirement-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  align-items: start;
}

.commission-ladder-toggle-row {
  align-items: center;
}

.commission-tier-list {
  list-style: none;
  padding: 0;
  margin: 0;
}

.commission-tier-row {
  align-items: end;
}

.task-requirement-grid label,
.task-requirement-grid textarea {
  width: 100%;
  min-width: 0;
}

@media (max-width: 720px) {
  .task-requirement-grid { grid-template-columns: 1fr; }
}
</style>
