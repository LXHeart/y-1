<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useGrassland } from '../composables/useGrassland'
import { parsePermissionMaterials } from '../types/grassland'
import type {
  Industry,
  MerchantAttachment,
  MaterialType,
  OrganizationQuota,
  PermissionRequest,
  PermissionTier,
  TaskUsage,
} from '../types/grassland'

/**
 * 商家权限与额度卡片（HLD D-05 的商家侧）。
 *
 * 解决的缺口：后端 Slice 2H + 2L 建了完整的升级审核流（材料 schema / SLA / 行业 / 申诉），
 * 但此前**没有任何 UI**——新商家只能靠种子脚本或 owner 自授予的 dev 后门拿到交易权限，
 * 产品上「注册 → 可发资金型任务」这条路是断的。
 *
 * 三件事：① 当前等级与额度（上限 vs 已用）；② 提交升级申请（材料按 tier+行业动态必填）；
 * ③ 我的申请列表（状态 / SLA / 驳回原因 / 申诉）。
 */

const props = defineProps<{ orgId: string; tier: PermissionTier; industry: string | null }>()
/** 审批通过后 org tier 变了，通知父组件重拉组织与额度。 */
const emit = defineEmits<{ changed: [] }>()

const grassland = useGrassland()

const quota = ref<OrganizationQuota | null>(null)
const usage = ref<TaskUsage | null>(null)
const requests = ref<PermissionRequest[]>([])
const merchantAttachments = ref<MerchantAttachment[]>([])
const notice = ref('')

const requestedTier = ref<PermissionTier>('basic_publish')
const formIndustry = ref<string>('')
const materials = ref<Record<string, string>>({})
/** 申诉中的申请 id；非空时表单切换为申诉模式。 */
const appealingId = ref('')
const appealNote = ref('')

const PERMISSION_DOCUMENT_TYPES = new Set([
  'business_license',
  'legal_person_id_front',
  'legal_person_id_back',
  'industry_license',
  'financial_qualification',
])

function permissionAttachmentIds(): string[] {
  return merchantAttachments.value
    .filter((item) => PERMISSION_DOCUMENT_TYPES.has(item.attachmentType))
    .map((item) => item.id)
}

const TIER_LABEL: Record<PermissionTier, string> = {
  draft: '草稿（不可发布）',
  basic_publish: '基础发布',
  finance_transaction: '资金交易',
}

const INDUSTRIES: { value: Industry; label: string }[] = [
  { value: 'catering', label: '餐饮' },
  { value: 'retail', label: '零售' },
  { value: 'beauty', label: '美业（受监管）' },
  { value: 'education', label: '教育（受监管）' },
  { value: 'e_commerce', label: '电商' },
  { value: 'healthcare', label: '医疗健康（受监管）' },
  { value: 'finance', label: '金融服务（受监管）' },
  { value: 'real_estate', label: '房地产（受监管）' },
  { value: 'travel', label: '旅游' },
  { value: 'children', label: '母婴儿童（受监管）' },
  { value: 'other', label: '其他' },
]

const MATERIAL_LABEL: Record<MaterialType, string> = {
  business_license: '营业执照',
  legal_representative: '法定代表人',
  financial_qualification: '财务资质',
  industry_license: '行业许可证',
  contact_info: '联系方式',
}

/** 受监管行业额外要求行业许可证——与后端 `Industry.requiresIndustryLicense` 同口径。 */
const REGULATED: string[] = ['beauty', 'education', 'healthcare', 'finance', 'real_estate', 'children']

/**
 * 必填材料集合。**镜像后端 `PermissionMaterialPolicy.requiredMaterialTypes`**，
 * 只用于前端提前提示（真正的校验在服务端，缺料 400）。两侧口径若漂移，以后端为准。
 */
const requiredMaterials = computed<MaterialType[]>(() => {
  const list: MaterialType[] = []
  if (requestedTier.value === 'basic_publish') {
    list.push('business_license', 'contact_info')
  } else if (requestedTier.value === 'finance_transaction') {
    list.push('business_license', 'legal_representative', 'financial_qualification', 'contact_info')
  }
  const effective = formIndustry.value || props.industry || ''
  if (REGULATED.includes(effective)) list.push('industry_license')
  return list
})

const missingMaterials = computed(() =>
  requiredMaterials.value.filter((t) => !(materials.value[t] || '').trim()))

/** 只能往上申请：低于或等于当前等级的选项无意义（后端也会 409）。 */
const upgradableTiers = computed<PermissionTier[]>(() => {
  const order: PermissionTier[] = ['draft', 'basic_publish', 'finance_transaction']
  return order.slice(order.indexOf(props.tier) + 1)
})

const hasPending = computed(() => requests.value.some((r) => r.status === 'pending' || r.status === 'under_review'))

const SLA_LABEL: Record<string, string> = {
  within: '审核中',
  at_risk: '临近超时',
  overdue: '已超时',
  completed: '已完成',
}

const STATUS_LABEL: Record<string, string> = {
  pending: '待审核',
  under_review: '审核中',
  approved: '已批准',
  rejected: '已驳回',
}

function yuan(cents: number): string {
  return (cents / 100).toFixed(2)
}

async function refresh(): Promise<void> {
  if (!props.orgId) return
  quota.value = await grassland.getQuota(props.orgId)
  // 用量是 **best-effort**：marketplace 按「断言里的 org」自查，而断言的 org 来自
  // identity_profile，与下拉选中的 org 不一定是同一个（多组织时对非绑定 org 会 403）。
  // 上限（identity 按成员关系授权）仍拿得到，所以这里只把用量降级为「—」，
  // 不让它以红条打断整张卡片。
  usage.value = await grassland.getUsage(props.orgId)
  if (!usage.value) grassland.clearError()
  const list = await grassland.listPermissionRequests(props.orgId)
  if (list) requests.value = list
  const attachments = await grassland.listMerchantAttachments(props.orgId)
  if (attachments) merchantAttachments.value = attachments
}

watch(() => props.orgId, refresh, { immediate: true })
// tier 变化（审批通过）后额度随之变，重拉一次
watch(() => props.tier, refresh)

/**
 * 手动刷新：**同时**让父组件重拉组织列表。
 *
 * `tier` 是父组件传下来的 prop（来自 orgs 列表），只刷新本卡片的话，
 * 平台在另一处批准后这里会出现「头部还写着草稿、额度却已按新等级」的自相矛盾状态
 * （浏览器实测踩到：批准后额度变 5/20，等级仍显示草稿）。
 * 不会成环：父组件 `loadOrganizations` 不回调本组件，tier 真变了才触发上面的 watch 再拉一次。
 */
async function manualRefresh(): Promise<void> {
  emit('changed')
  await refresh()
}

async function submit(): Promise<void> {
  notice.value = ''
  if (missingMaterials.value.length > 0) return

  if (appealingId.value) {
    const appealed = await grassland.appealPermissionRequest(
      props.orgId, appealingId.value, { ...materials.value }, appealNote.value.trim() || undefined,
      permissionAttachmentIds())
    if (!appealed) return
    notice.value = '申诉已提交，将重新进入审核队列'
  } else {
    const created = await grassland.createPermissionRequest(props.orgId, {
      requestedTier: requestedTier.value,
      materials: { ...materials.value },
      industry: formIndustry.value || undefined,
      attachmentIds: permissionAttachmentIds(),
    })
    if (!created) return
    notice.value = `升级申请已提交（目标等级 ${TIER_LABEL[created.requestedTier]}）`
  }
  cancelAppeal()
  materials.value = {}
  emit('changed')
  await refresh()
}

function startAppeal(req: PermissionRequest): void {
  appealingId.value = req.id
  requestedTier.value = req.requestedTier
  formIndustry.value = req.industry || ''
  // 预填原材料，便于只改被驳回的那项。
  // materials 在响应里是 JSON 字符串（见类型注释），必须先解析——直接展开会得到逐字符的键。
  materials.value = { ...parsePermissionMaterials(req.materials) }
  notice.value = ''
}

function cancelAppeal(): void {
  appealingId.value = ''
  appealNote.value = ''
}
</script>

<template>
  <article class="mp">
    <header class="mp-head">
      <h3>商家权限与额度</h3>
      <button type="button" class="mp-quiet" :disabled="grassland.loading.value" @click="manualRefresh">刷新</button>
    </header>

    <p v-if="grassland.error.value" class="mp-alert mp-err" role="alert">{{ grassland.error.value }}</p>
    <p v-if="notice" class="mp-alert mp-ok">{{ notice }}</p>

    <!-- 当前等级 + 额度：上限来自 identity，已用来自 marketplace -->
    <section class="mp-quota">
      <p class="mp-tier">
        当前等级 <strong>{{ TIER_LABEL[props.tier] }}</strong>
        <span v-if="props.industry" class="mp-hint">· 行业 {{ props.industry }}</span>
      </p>
      <dl v-if="quota" class="mp-grid">
        <div>
          <dt>活跃任务</dt>
          <dd>{{ usage ? `${usage.activeTasks} / ${usage.maxActiveTasks}（余 ${usage.remainingActiveTasks}）` : `— / ${quota.maxActiveTasks}` }}</dd>
        </div>
        <div>
          <dt>本月新建</dt>
          <dd>{{ usage ? `${usage.monthlyTasks} / ${usage.maxMonthlyTasks}（余 ${usage.remainingMonthlyTasks}）` : `— / ${quota.maxMonthlyTasks}` }}</dd>
        </div>
        <div>
          <dt>单笔赏金上限</dt>
          <dd>{{ quota.maxTxAmountCents > 0 ? `¥${yuan(quota.maxTxAmountCents)}` : '不可交易' }}</dd>
        </div>
      </dl>
      <p class="mp-hint">
        额度上限由等级决定；超限时发布会被拒（403/409）。
        <span v-if="quota && !usage">已用量仅对当前商家身份绑定的组织可见。</span>
      </p>
    </section>

    <!-- 升级申请 / 申诉 -->
    <section v-if="upgradableTiers.length > 0 || appealingId" class="mp-apply">
      <h4>{{ appealingId ? '申诉（补正材料后重新提交）' : '申请升级' }}</h4>

      <p v-if="hasPending && !appealingId" class="mp-hint">已有待审核的申请，审核完成后可再提交。</p>

      <div class="mp-row">
        <label>目标等级
          <select v-model="requestedTier" :disabled="!!appealingId">
            <option v-for="t in upgradableTiers" :key="t" :value="t">{{ TIER_LABEL[t] }}</option>
          </select>
        </label>
        <label>行业
          <select v-model="formIndustry">
            <option value="">（沿用组织行业）</option>
            <option v-for="i in INDUSTRIES" :key="i.value" :value="i.value">{{ i.label }}</option>
          </select>
        </label>
      </div>

      <div v-for="m in requiredMaterials" :key="m" class="mp-row">
        <label class="mp-mat">
          {{ MATERIAL_LABEL[m] }} <span class="mp-req">*</span>
          <input v-model="materials[m]" :placeholder="`填写${MATERIAL_LABEL[m]}`" />
        </label>
      </div>

      <div v-if="appealingId" class="mp-row">
        <label class="mp-mat">申诉说明
          <input v-model="appealNote" placeholder="说明补正了什么（可选）" />
        </label>
      </div>

      <p v-if="missingMaterials.length > 0" class="mp-hint">
        还需填写：{{ missingMaterials.map((m) => MATERIAL_LABEL[m]).join('、') }}
      </p>

      <div class="mp-row">
        <button
          type="button"
          :disabled="grassland.loading.value || missingMaterials.length > 0"
          @click="submit"
        >{{ appealingId ? '提交申诉' : '提交申请' }}</button>
        <button v-if="appealingId" type="button" class="mp-quiet" @click="cancelAppeal">取消</button>
      </div>
    </section>
    <p v-else class="mp-hint">已是最高等级，无需升级。</p>

    <!-- 我的申请 -->
    <section v-if="requests.length > 0" class="mp-list">
      <h4>我的申请</h4>
      <table class="mp-table">
        <thead><tr><th>目标等级</th><th>状态</th><th>时效</th><th>审核意见</th><th>操作</th></tr></thead>
        <tbody>
          <tr v-for="r in requests" :key="r.id">
            <td>
              {{ TIER_LABEL[r.requestedTier] }}
              <span v-if="r.originalRequestId" class="mp-tag">申诉件</span>
            </td>
            <td>{{ STATUS_LABEL[r.status] || r.status }}</td>
            <td :class="{ 'mp-overdue': r.slaStatus === 'overdue' }">{{ SLA_LABEL[r.slaStatus] || r.slaStatus }}</td>
            <td>{{ r.reviewNote || '—' }}</td>
            <td>
              <button
                v-if="r.status === 'rejected'"
                type="button" class="mp-quiet"
                :disabled="grassland.loading.value"
                @click="startAppeal(r)"
              >申诉</button>
            </td>
          </tr>
        </tbody>
      </table>
    </section>
  </article>
</template>

<style scoped>
.mp { border: 1px solid var(--color-border); border-radius: 10px; padding: 14px; display: flex; flex-direction: column; gap: 12px; }
.mp-head { display: flex; justify-content: space-between; align-items: center; }
.mp-head h3 { margin: 0; font-size: 15px; }
.mp-alert { margin: 0; padding: 7px 11px; border-radius: 6px; font-size: 13px; }
.mp-err { background: color-mix(in srgb, var(--color-danger) 14%, transparent); color: var(--color-danger); }
.mp-ok { background: color-mix(in srgb, var(--color-success) 14%, transparent); color: var(--color-success); }
.mp-quota { display: flex; flex-direction: column; gap: 8px; }
.mp-tier { margin: 0; font-size: 13px; }
.mp-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(120px, 1fr)); gap: 10px; margin: 0; }
.mp-grid div { display: flex; flex-direction: column; gap: 2px; }
.mp-grid dt { font-size: 11px; opacity: 0.6; }
.mp-grid dd { margin: 0; font-size: 13px; font-weight: 500; font-variant-numeric: tabular-nums; }
.mp-apply, .mp-list { display: flex; flex-direction: column; gap: 8px; padding-top: 10px; border-top: 1px solid var(--color-border); }
.mp-apply h4, .mp-list h4 { margin: 0; font-size: 13px; }
.mp-row { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.mp-mat { flex: 1; display: flex; align-items: center; gap: 8px; font-size: 13px; }
.mp-mat input { flex: 1; min-width: 140px; }
.mp-req { color: var(--color-danger); }
.mp-table { width: 100%; border-collapse: collapse; font-size: 13px; }
.mp-table th, .mp-table td { text-align: left; padding: 6px 8px; border-bottom: 1px solid var(--color-border); }
.mp-tag { font-size: 11px; padding: 1px 6px; border-radius: 4px; background: var(--color-surface-strong); margin-left: 4px; }
.mp-overdue { color: var(--color-danger); }
.mp-hint { margin: 0; font-size: 12px; opacity: 0.62; }
label { display: flex; align-items: center; gap: 6px; font-size: 13px; }
input, select { padding: 6px 10px; border: 1px solid var(--color-border); background: var(--color-surface); color: var(--color-text); border-radius: 6px; font-size: 13px; }
button { padding: 6px 14px; border: 1px solid var(--color-border); background: transparent; color: var(--color-text); border-radius: 6px; cursor: pointer; font-size: 13px; }
button:hover:not(:disabled) { border-color: var(--color-border-hover); background: var(--color-surface-hover); }
button:disabled { opacity: 0.5; cursor: not-allowed; }
.mp-quiet { opacity: 0.75; font-size: 12px; padding: 4px 10px; }
</style>
