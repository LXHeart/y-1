<template>
  <!-- 抽屉化（不再常驻页签顶部）：发布/编辑是偶发动作，让位给每天要看的任务与报名列表。
       Teleport 到 body 规避祖先 backdrop-filter 造成的 fixed containing block 问题；
       根节点补挂 .gl-field —— 田垄系统的表单样式全部写在 `.gl-field xxx` 后代选择器下
       （src/style.css），脱离工作台根后不补这个类，整个抽屉的输入框/按钮会裸奔。 -->
  <Teleport to="body">
    <!-- 关闭途径收窄（问题 1）：遮罩空白处不再关闭（误触即丢表单）；只有 取消 / × / 提交审核 / 存为草稿 四个出口，
         Esc 等同 ×——脏表单先过三选一确认，干净表单直接关。 -->
    <div v-if="open" class="gl-field task-drawer-overlay">
      <section class="task-drawer" role="dialog" aria-modal="true" aria-labelledby="task-drawer-title">
        <header class="task-drawer-head">
          <div class="task-drawer-head-copy">
            <h3 id="task-drawer-title">{{ drawerTitle }}</h3>
            <p class="gl-hint">{{ drawerHint }}</p>
          </div>
          <button type="button" class="task-drawer-close" aria-label="关闭任务表单" @click="requestClose">×</button>
        </header>

        <div class="task-drawer-body">
    <!-- 提交失败/本地校验错误就地表态：写到背景页会被抽屉盖住，等于「点了没反应」 -->
    <p v-if="notice" class="gl-alert gl-alert-error" role="alert">{{ notice }}</p>
    <div class="gl-row">
      <label>资源范围
        <select name="task-scope" :value="selectedStoreId" :disabled="Boolean(editingDraft || revisingTask)" @change="$emit('change-store', ($event.target as HTMLSelectElement).value)">
          <option v-if="hasOrganizationAccess" value="">主体级任务</option>
          <option v-for="store in stores" :key="store.id" :value="store.id">门店：{{ store.name }}</option>
        </select>
      </label>
      <input ref="titleInputRef" :value="form.title" aria-label="任务标题" name="task-title" autocomplete="off" placeholder="任务标题" @input="updateField('title', ($event.target as HTMLInputElement).value)" />
      <label>发布平台
        <select name="task-platform" :value="form.platform ?? ''" aria-label="发布平台（PRD §2.2 九平台）" @change="updateField('platform', ($event.target as HTMLSelectElement).value)">
          <option value="">未指定</option>
          <option v-for="p in TASK_PLATFORMS" :key="p.id" :value="p.id">{{ p.label }}</option>
        </select>
      </label>
      <label>内容形式
        <select
          name="task-content-form"
          :value="form.contentForm"
          :disabled="!form.platform"
          :aria-disabled="!form.platform"
          @change="updateField('contentForm', ($event.target as HTMLSelectElement).value)"
        >
          <option v-if="!form.platform" value="" disabled>请先选择发布平台</option>
          <option v-for="opt in contentFormOptions" :key="opt" :value="opt">{{ CONTENT_FORM_LABELS[opt] }}</option>
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
    <!-- 任务书 #62 P4：知乎专属。填写则该任务交付「知乎回答」，推荐官进创作流即锁回答模式。 -->
    <div v-if="zhihuQuestionVisible" class="gl-row">
      <label>目标问题（选填，填写则交付知乎回答）
        <textarea
          :value="form.questionText ?? ''"
          aria-label="目标问题（选填，填写则交付知乎回答）"
          name="task-question-text"
          data-testid="task-question-text"
          autocomplete="off"
          rows="3"
          placeholder="粘贴知乎问题链接或直接手输问题原文（知乎不开放抓取，标题请手动填写）"
          @input="updateQuestionText(($event.target as HTMLTextAreaElement).value)"
        />
      </label>
      <p v-if="questionRefHint" class="gl-hint" data-testid="task-question-ref">
        已识别问题链接 #{{ questionRefHint }}，标题请手动填写
      </p>
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
    <div class="gl-row" role="radiogroup" aria-label="付费方式（三选一）">
      <span class="payment-mode-label">付费方式</span>
      <label class="payment-mode-option">
        <input type="radio" name="task-payment-mode" value="commission" :checked="form.paymentMode === 'commission'" @change="switchPaymentMode('commission')" />
        任务量佣金（达标即给 / 阶梯）
      </label>
      <label class="payment-mode-option">
        <input type="radio" name="task-payment-mode" value="freebie" :checked="form.paymentMode === 'freebie'" @change="switchPaymentMode('freebie')" />
        霸王餐 / 实物兑换
      </label>
      <span class="gl-hint">三种模式三选一，不可组合</span>
    </div>
    <div class="gl-row">
      <label>名额 <input :value="form.maxSlots" name="task-max-slots" autocomplete="off" type="number" min="1" @input="updateField('maxSlots', Number(($event.target as HTMLInputElement).value))" /></label>
      <label v-if="form.paymentMode === 'commission'">赏金 ¥<input :value="form.bountyYuan" name="task-bounty" autocomplete="off" type="number" min="0" :disabled="!canPublishBounty" @input="updateField('bountyYuan', Number(($event.target as HTMLInputElement).value))" /></label>
      <label v-if="form.paymentMode === 'freebie'">霸王餐押金 ¥<input :value="form.freebieDepositYuan" name="task-freebie-deposit" autocomplete="off" type="number" min="0" :disabled="!canPublishBounty" @input="updateField('freebieDepositYuan', Number(($event.target as HTMLInputElement).value))" /></label>
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
    <div v-if="form.paymentMode === 'commission'" class="gl-row commission-ladder-toggle-row">
      <label>阶梯佣金
        <input type="checkbox" aria-label="启用阶梯佣金" name="commission-ladder-enabled" :checked="ladderForm.enabled" @change="patchCommissionLadder({ enabled: ($event.target as HTMLInputElement).checked })" />
      </label>
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
    <p class="gl-hint">付费方式<b>三选一</b>（PRD §2.2）：任务量佣金（达标即给 / 阶梯）或霸王餐押金，不可组合；到店核销佣金分成在「资金与经营 → 到店套餐与核销」以套餐形式发布。赏金 &gt; 0 的任务为资金型：接受报名时会走资金预留 Saga（异步）。「自动通过」开启后对存量待处理报名生效；资金不足或名额满时回退人工处理。草稿不占发布额度、不需资金权限。已发布任务在<b>无人报名成功</b>时可「编辑」出新版本，改赏金/平台只影响新报名；有人报名成功后任务冻结不可再修改，已接受的履约按其接受时的金额结算（snapshot-pinning）。</p>
        </div>

        <!-- 提交条 sticky 在抽屉底：长表单滚动时主操作始终可达 -->
        <footer class="task-drawer-foot">
          <div class="gl-row">
            <button v-if="!revisingTask" type="button" class="gl-btn-primary" :disabled="!activeOrgId || loading" @click="$emit('publish')">提交审核</button>
            <button type="button" :disabled="!activeOrgId || loading" @click="$emit('save-draft')">{{ revisingTask ? '保存修订' : (editingDraft ? '保存草稿' : '存为草稿') }}</button>
            <button type="button" :disabled="loading" @click="requestClose">{{ editingDraft || revisingTask ? '取消编辑' : '取消' }}</button>
          </div>
        </footer>
      </section>

      <!-- 三选一离开确认（复用全局 modal 骨架，z-100 盖过抽屉 z-80）：脏表单才出现；
           表单没改动过直接关，不打扰。文案按模式分派（新建/编辑草稿=存草稿，修订=保存修订）。 -->
      <div v-if="confirmExitOpen" class="modal-overlay" @click.self="confirmExitOpen = false">
        <div class="modal-card" role="dialog" aria-modal="true" aria-labelledby="task-exit-title" aria-describedby="task-exit-copy">
          <div class="modal-header">
            <h4 class="modal-title" id="task-exit-title">离开任务表单？</h4>
          </div>
          <div class="modal-body">
            <p id="task-exit-copy" class="task-exit-copy">{{ exitConfirmMessage }}</p>
            <div class="modal-actions task-exit-actions">
              <button type="button" class="btn-cancel" @click="confirmExitOpen = false">继续编辑</button>
              <button type="button" class="btn-confirm danger" @click="discardAndExit">直接退出（作废）</button>
              <button ref="exitPrimaryBtnRef" type="button" class="btn-confirm" @click="saveDraftAndExit">{{ saveExitLabel }}</button>
            </div>
          </div>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { AI_PLATFORM_DEFINITIONS, getPlatform, normalizePlatformId } from '../../../config/ai-platform-capabilities'
import type { Store } from '../../../types/grassland'
import { formatYuan, yuanToCents } from '../../../lib/money'
import { extractZhihuQuestionRef } from '../../../lib/zhihu-question'
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
  /** 付费方式三选一：commission=任务量佣金，freebie=霸王餐/实物兑换。 */
  paymentMode: 'commission' | 'freebie'
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
  /** 任务书 #62 P4：目标问题原文（仅 platform=zhihu；填写则该任务交付知乎回答）。 */
  questionText?: string
  /** 目标问题溯源 id（从粘贴链接本地提取，纯数字；不发任何请求）。 */
  questionRef?: string
}

const props = defineProps<{
  /** 抽屉开合由父组件持有：三种模式（新建 / 编辑草稿 / 修订已发布）共用同一实例。 */
  open: boolean
  form: TaskFormData
  editingDraft: { id: string; version: number } | null
  revisingTask: { id: string; version: number } | null
  stores: Store[]
  selectedStoreId: string
  activeOrgId: string
  hasOrganizationAccess: boolean
  canPublishBounty: boolean
  loading: boolean
  /** 抽屉内告警条（提交失败 / 本地校验错误）：失败信息必须落在抽屉里，不能写到被盖住的背景页。可选，缺省不显示。 */
  notice?: string
}>()

/** 任务书 #46：赏金与押金可组合（两腿独立）；仍互斥的是阶梯 × 押金（#25）。 */
/** 任务书 #23 R6：contentForm=interaction 时展示目标链接 + 动作类型两个必填字段。 */
/** 任务书 #25：阶梯佣金（赏金模式）与霸王餐押金互斥——freebie>0 禁用阶梯开关，阶梯启用禁用押金输入。 */
/**
 * 发布平台下拉（PRD §2.2：小红书/抖音/快手/视频号/公众号/知乎/B站/大众点评/朋友圈）。
 * 与 AI 创作中心的平台表同源（config/ai-platform-capabilities），存 canonical id——
 * 任务上下文带入 AI 中心时 normalizePlatformId 可直接归一。
 */
const TASK_PLATFORMS = AI_PLATFORM_DEFINITIONS.map((platform) => ({
  id: platform.id,
  // PRD §2.2 的平台名（B 站在 AI 中心表里叫 Bilibili，任务表单按 PRD 口径显示）
  label: platform.id === 'bilibili' ? 'B站' : platform.label,
}))

/** PRD §2.2 任务内容形式三类（article 不在任务分类）。 */
const CONTENT_FORM_LABELS: Readonly<Record<string, string>> = {
  image: '图文种草',
  video: '视频种草',
  interaction: '点赞互动',
}

/**
 * 内容形式随平台能力裁剪（PRD §4.2 平台×形式表，与 AI 中心同源）：
 * 图文能力=graphic/image-text，视频能力=video/video-text；点赞互动无需创作内容，
 * 所有平台可用。平台未指定或为存量自由文本（无法归一）时不裁剪。
 */
const contentFormOptions = computed<string[]>(() => {
  const platformId = normalizePlatformId(props.form.platform || '')
  const forms = platformId ? getPlatform(platformId)?.forms : null
  if (!platformId) return [] // 未选平台：内容形式为空且不可选（先定平台，再定形式）
  if (!forms) return ['image', 'video', 'interaction']
  const hasGraphic = forms.some((form) => form.id === 'graphic' || form.id === 'image-text')
  const hasVideo = forms.some((form) => form.id === 'video' || form.id === 'video-text')
  return [hasGraphic ? 'image' : null, hasVideo ? 'video' : null, 'interaction']
    .filter((form): form is string => form !== null)
})

// 平台是内容形式的前置：未选平台 → 形式清空；选了平台 → 形式不被支持时自动落回
// 首个可用形式（如视频种草 → 公众号）。初值纠正放 onMounted：setup 期 emit 尚未
// 初始化，immediate watcher 会踩 TDZ。
function reconcileContentForm(): void {
  const options = contentFormOptions.value
  if (options.length === 0) {
    if (props.form.contentForm !== '') updateField('contentForm', '')
    return
  }
  if (!options.includes(props.form.contentForm)) updateField('contentForm', options[0])
}
onMounted(reconcileContentForm)
watch([() => props.form.platform, () => props.form.contentForm], reconcileContentForm)

/**
 * 任务书 #62 P4：目标问题只对知乎有意义（回答挂在知乎问题下），非知乎携带后端 422。
 * 平台改走非知乎时清空残留（见下方 watch）——否则「先填知乎问题再改平台」会撞 422。
 */
const zhihuQuestionVisible = computed(() => normalizePlatformId(props.form.platform || '') === 'zhihu')
const questionRefHint = computed(() => props.form.questionRef || '')

/** 问题输入：原文照存（手输为准），同步刷新本地溯源 id。零网络请求（#62 §3.7）。 */
function updateQuestionText(value: string): void {
  updateField('questionText', value)
  updateField('questionRef', extractZhihuQuestionRef(value))
}

watch(zhihuQuestionVisible, (visible) => {
  if (visible) return
  if (props.form.questionText) updateField('questionText', '')
  if (props.form.questionRef) updateField('questionRef', '')
})

const interactionForm = computed(() => props.form.contentForm === 'interaction')
const bountyActive = computed(() => props.form.bountyYuan > 0)
const freebieActive = computed(() => props.form.freebieDepositYuan > 0)
/** 阶梯表单元数据；父组件（Task 3）未接入时回退默认关闭表单，保证旧挂载点不受影响。 */
const ladderForm = computed<CommissionLadderFormData>(
  () => props.form.commissionLadder ?? emptyCommissionLadderForm(),
)
const fundingHint = computed(() => {
  return freebieActive.value
    ? `霸王餐押金模式：推荐官报名被接受时从钱包预付 ${formatYuan(yuanToCents(props.form.freebieDepositYuan))}，达标（核实+商家确认）全额返还，未达标补偿商家`
    : '赏金模式：商家出资托管，推荐官达标后结算（可切换为阶梯佣金按档计酬）'
})

/** 付费方式三选一：切换即清另一模式的资金字段（押金↔赏金/阶梯互斥，组合无法成立）。 */
function switchPaymentMode(mode: 'commission' | 'freebie'): void {
  if (props.form.paymentMode === mode) return
  updateField('paymentMode', mode)
  if (mode === 'freebie') {
    updateField('bountyYuan', 0)
    if (ladderForm.value.enabled) patchCommissionLadder({ enabled: false })
  } else {
    updateField('freebieDepositYuan', 0)
  }
}

const emit = defineEmits<{
  'update:field': [field: string, value: string | number | null]
  'update:commission-ladder': [value: CommissionLadderFormData]
  'change-store': [storeId: string]
  publish: []
  'save-draft': []
  /** 用户确认关闭（干净表单直接关 / 三选一确认的「直接退出」）——父组件收到即可清表单并关抽屉。 */
  close: []
}>()

/** 抽屉标题与副文案按三种模式分派（原先挤在一行 h3 里的 hint，抽屉里有位置说清）。 */
const drawerTitle = computed(() => {
  if (props.revisingTask) return '修订已发布任务'
  if (props.editingDraft) return '编辑任务草稿'
  return '发布任务'
})
const drawerHint = computed(() => {
  if (props.revisingTask) return '正在修订已发布任务（保存出新版本）：改赏金/平台只影响新报名，已接受的履约按接受时金额结算'
  if (props.editingDraft) return '正在编辑草稿（保存后仍为草稿，需回任务列表点「提交审核」）'
  return '提交后经平台内容审核，通过后在大厅上架'
})

// ---------- 未保存变更守卫（Web Interface Guidelines：带未保存修改离开需警告） ----------
// SPA 内切标签页由 KeepAlive 保活、状态不丢，守卫只针对整页刷新/关闭（beforeunload）。
// 基线随编辑上下文重置：进入/退出草稿·修订时父组件整体换表单值，那一刻的差异不是用户输入。
// 基线必须是响应式：computed 会缓存首次求值，普通 let 赋值触发不了重算——
// 此前干净表单的 dirty 一直是陈旧的 true（旧版 Esc 必弹 confirm，缺陷不可见）。
const formBaseline = ref('')
const recordFormBaseline = (): void => { formBaseline.value = JSON.stringify(props.form) }
const formDirty = computed(() => JSON.stringify(props.form) !== formBaseline.value)

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

// ---------- 三选一离开确认（替换 window.confirm：原生框只有「丢弃/留下」，没有存草稿出口） ----------
const confirmExitOpen = ref(false)
const exitPrimaryBtnRef = ref<HTMLButtonElement | null>(null)

/** 确认文案按模式分派：修订模式问「保存修订」，新建/编辑草稿问「存为草稿」——保存链路复用 saveDraft 的分派。 */
const exitConfirmMessage = computed(() => props.revisingTask
  ? '返回将清空当前已填内容，要先保存修订吗？'
  : '返回将清空当前已填内容，要先存为草稿吗？')
const saveExitLabel = computed(() => props.revisingTask
  ? '保存修订并退出'
  : props.editingDraft ? '保存草稿并退出' : '存为草稿并退出')

/** 确认框弹起时焦点落到主出口（存草稿并退出）——键盘用户不必再爬回抽屉。 */
watch(confirmExitOpen, (open) => {
  if (open) void nextTick(() => exitPrimaryBtnRef.value?.focus())
})

/** 存草稿并退出：复用提交条的保存链路（父组件成功才关抽屉，失败留在抽屉里改、错误见告警条）。 */
function saveDraftAndExit(): void {
  confirmExitOpen.value = false
  emit('save-draft')
}

/** 直接退出（作废）：丢弃已填内容并关抽屉。 */
function discardAndExit(): void {
  confirmExitOpen.value = false
  emit('close')
}

// ---------- 抽屉开合：脏状态确认 / Esc / 初始焦点 / 焦点归还 / 背景锁滚 ----------
// 关闭途径只有 取消 / × / 提交审核 / 存为草稿：脏表单统一走三选一确认（Esc=×），干净表单直接关不加摩擦。
function requestClose(): void {
  if (!formDirty.value) {
    emit('close')
    return
  }
  confirmExitOpen.value = true
}

function onDrawerKeydown(event: KeyboardEvent): void {
  if (event.key !== 'Escape') return
  // 确认框开着时 Esc 只收确认框（=继续编辑），不穿透到抽屉再弹一次。
  if (confirmExitOpen.value) {
    confirmExitOpen.value = false
    return
  }
  requestClose()
}

/** 打开前的焦点元素（一般是触发按钮）：关闭后归还，键盘用户不被丢回文档开头。 */
let lastFocused: HTMLElement | null = null
const titleInputRef = ref<HTMLInputElement | null>(null)

watch(() => props.open, (open) => {
  if (open) {
    // 编辑/修订时父组件先回填表单再置 open：此刻记基线，"脏"只算抽屉内的用户输入。
    recordFormBaseline()
    lastFocused = document.activeElement instanceof HTMLElement ? document.activeElement : null
    document.body.style.overflow = 'hidden'
    window.addEventListener('keydown', onDrawerKeydown)
    void nextTick(() => titleInputRef.value?.focus())
    return
  }
  confirmExitOpen.value = false
  document.body.style.overflow = ''
  window.removeEventListener('keydown', onDrawerKeydown)
  lastFocused?.focus()
  lastFocused = null
}, { immediate: true })

onBeforeUnmount(() => {
  document.body.style.overflow = ''
  window.removeEventListener('keydown', onDrawerKeydown)
})

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
/* 右侧抽屉：z-index 80 落在工作台内容之上、治理台 .modal-overlay(100) 之下。
   宽度取 720px —— 「必须包含/禁止内容/指标要求/凭证要求」是 2 列 textarea 网格，
   仓库既有弹窗宽度（440 / 560）装不下。 */
.task-drawer-overlay {
  position: fixed;
  inset: 0;
  z-index: 80;
  display: flex;
  justify-content: flex-end;
  background: var(--color-overlay);
  backdrop-filter: blur(4px);
  -webkit-backdrop-filter: blur(4px);
}

.task-drawer {
  display: flex;
  flex-direction: column;
  width: min(720px, 100vw);
  height: 100%;
  background: var(--color-surface);
  border-left: 1px solid var(--color-border);
  box-shadow: var(--shadow-elevated);
}

.task-drawer-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-sm);
  padding: var(--space-md);
  border-bottom: 1px solid var(--color-border);
}

.task-drawer-head-copy { display: flex; flex-direction: column; gap: var(--space-xs); min-width: 0; }

.task-drawer-close {
  flex: 0 0 auto;
  width: 32px;
  min-height: 32px;
  padding: 0;
  font-size: var(--text-lg);
  line-height: 1;
  color: var(--color-text-muted);
}

/* 表单主体：原 .gl-tile 的纵向节奏（flex column + gap）在此重建——抽屉不再套磁贴 */
.task-drawer-body {
  flex: 1;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: var(--space-sm);
  padding: var(--space-md);
}

.task-drawer-foot {
  padding: var(--space-sm) var(--space-md);
  border-top: 1px solid var(--color-border);
  background: var(--color-surface);
}

@media (max-width: 720px) {
  .task-drawer { border-left: none; }
}

/* 三选一离开确认：四个文案长度不一的按钮在 440px 卡片内可能放不下，允许换行兜底 */
.task-exit-actions { flex-wrap: wrap; }
.task-exit-copy { margin: 0; font-size: var(--text-sm); color: var(--color-text); line-height: 1.6; }

.payment-mode-label { font-size: var(--text-sm); font-weight: 600; color: var(--color-text-secondary); }
.payment-mode-option { display: inline-flex; align-items: center; gap: 6px; font-size: var(--text-sm); color: var(--color-text-secondary); cursor: pointer; }
h3 { margin: 0; font-size: var(--text-base); font-weight: 700; letter-spacing: -0.01em; }

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
