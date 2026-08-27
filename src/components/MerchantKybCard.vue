<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useGrassland } from '../composables/useGrassland'
import StoreMediaManager from './StoreMediaManager.vue'
import type {
  MerchantProfile,
  MerchantAttachment,
  MerchantAttachmentType,
  OrgKybSummary,
  WithdrawalAccount,
  StoreProfile,
} from '../types/grassland'

interface Props {
  orgId: string
  /** 独立门店经理模式：只显示门店资料 tab（STAFF 读/MANAGER 写的后端语义），不拉组织级商家资料/收款账户。 */
  storeOnly?: boolean
  /** 外部注入门店列表（纯门店经理无组织成员身份，listStores 会被 403，改由工作台用 manager scopes 注入）。 */
  stores?: Array<{ id: string; name: string }>
}

const props = withDefaults(defineProps<Props>(), {
  storeOnly: false,
  stores: undefined,
})
const emit = defineEmits<{
  changed: []
  /** 认证状态冒泡给工作台的概览节（storeOnly 模式下 merchantStatus 恒为 null）。 */
  summary: [OrgKybSummary]
}>()

const grassland = useGrassland()
const merchantReadError = ref('')
const storeReadError = ref('')
const merchantProfileLoaded = ref(false)
const storeProfileLoaded = ref(false)
let organizationLoadVersion = 0
let storeOperationVersion = 0

// 当前标签页
type KybTab = 'merchant' | 'withdrawal' | 'store'
const activeTab = ref<KybTab>(props.storeOnly ? 'store' : 'merchant')

// 商家资料
const merchantProfile = ref<MerchantProfile | null>(null)
const merchantAttachments = ref<MerchantAttachment[]>([])
const merchantForm = ref({
  legalName: '',
  unifiedSocialCreditCode: '',
  businessType: '',
  legalPersonName: '',
  legalPersonIdNumber: '',
  registeredCapitalYuan: '',
  establishmentDate: '',
  businessAddressProvince: '',
  businessAddressCity: '',
  businessAddressDistrict: '',
  businessAddressDetail: '',
  contactPhone: '',
  contactEmail: '',
})

// 收款账户
const withdrawalAccounts = ref<WithdrawalAccount[]>([])
const accountForm = ref({
  accountType: 'bank_card' as const,
  accountName: '',
  accountNumber: '',
  bankName: '',
  branchName: '',
})

// 门店列表（从父组件传入或自行获取）
const storeOptions = ref<{ id: string; name: string }[]>([])
const selectedStoreId = ref('')
const storeProfile = ref<StoreProfile | null>(null)
const storeForm = ref({
  addressProvince: '',
  addressCity: '',
  addressDistrict: '',
  addressDetail: '',
  phone: '',
  description: '',
  // 任务书 #24：PRD §2.1 营销字段；列表类用 textarea 换行分隔（与任务表单 lines() 同约定）。
  categories: '',
  signatureItems: '',
  sellingPoints: '',
  mustEmphasize: '',
  forbiddenPhrases: '',
  allowedTags: '',
  brandTone: '',
  priceRange: '',
  averageSpendYuan: '',
  visitNotes: '',
})

// 状态映射
const statusLabels: Record<string, string> = {
  draft: '草稿',
  pending: '待审核',
  under_review: '审核中',
  approved: '已通过',
  rejected: '已拒绝',
  active: '启用',
  inactive: '停用',
}

const accountTypeLabels: Record<string, string> = {
  bank_card: '银行卡',
  alipay: '支付宝',
  wechat: '微信',
}

// 计算属性
const canSubmitMerchant = computed(() => {
  const f = merchantForm.value
  const hasIdNumber = Boolean(f.legalPersonIdNumber || merchantProfile.value?.legalPersonIdNumberMasked)
  return Boolean(f.legalName && f.unifiedSocialCreditCode && f.legalPersonName && hasIdNumber)
})

const canEditMerchant = computed(() => merchantProfileLoaded.value && !merchantReadError.value
  && (!merchantProfile.value
    || merchantProfile.value.status === 'draft'
    || merchantProfile.value.status === 'rejected'))

const canEditPermissionSupplements = computed(() => merchantProfileLoaded.value && !merchantReadError.value
  && (!merchantProfile.value || !['pending', 'under_review'].includes(merchantProfile.value.status)))

function canEditAttachment(attachmentType: MerchantAttachmentType): boolean {
  return attachmentType === 'industry_license' || attachmentType === 'financial_qualification'
    ? canEditPermissionSupplements.value
    : canEditMerchant.value
}

const canEditStore = computed(() => storeProfileLoaded.value && !storeReadError.value
  && (!storeProfile.value
    || ['draft', 'rejected', 'inactive'].includes(storeProfile.value.status)))

const canSubmitStore = computed(() => storeProfile.value !== null
  && ['draft', 'rejected'].includes(storeProfile.value.status))

function parseAddress(value: string | null): Record<string, string> {
  if (!value) return {}
  try {
    const parsed = JSON.parse(value) as unknown
    return parsed !== null && typeof parsed === 'object' ? parsed as Record<string, string> : {}
  } catch {
    return {}
  }
}

function emptyStoreForm(): typeof storeForm.value {
  return {
    addressProvince: '', addressCity: '', addressDistrict: '', addressDetail: '',
    phone: '', description: '',
    categories: '', signatureItems: '', sellingPoints: '', mustEmphasize: '',
    forbiddenPhrases: '', allowedTags: '', brandTone: '', priceRange: '',
    averageSpendYuan: '', visitNotes: '',
  }
}

/** 换行分隔约定（同任务表单）：按行拆、trim、去空、去重。 */
function storeFormLines(value: string): string[] {
  return [...new Set(value.split(/\r?\n/).map((item) => item.trim()).filter(Boolean))]
}

/** 人均消费元 → cents；非法/空 → undefined（清空）。number 入参兼容 type=number 的 v-model 自动转换。 */
function averageSpendToCents(value: string | number): number | undefined {
  const text = String(value ?? '').trim()
  const yuan = Number(text)
  if (text === '' || !Number.isFinite(yuan) || yuan < 0) return undefined
  return Math.round(yuan * 100)
}

// 方法
function isCurrentOrganization(orgId: string, version: number): boolean {
  return props.orgId === orgId && organizationLoadVersion === version
}

function isCurrentStoreOperation(
  orgId: string,
  organizationVersion: number,
  storeId: string,
  operationVersion: number,
): boolean {
  return isCurrentOrganization(orgId, organizationVersion)
    && selectedStoreId.value === storeId
    && storeOperationVersion === operationVersion
}

async function loadMerchantProfile(orgId: string, version: number): Promise<void> {
  merchantReadError.value = ''
  merchantProfileLoaded.value = false
  try {
    const profile = await grassland.getMerchantProfile(orgId)
    if (!isCurrentOrganization(orgId, version)) return
    merchantProfileLoaded.value = true
    if (profile) {
      merchantProfile.value = profile
      // 回填表单
      const address = parseAddress(profile.businessAddress)
      merchantForm.value = {
        legalName: profile.legalName || '',
        unifiedSocialCreditCode: profile.unifiedSocialCreditCode || '',
        businessType: profile.businessType || '',
        legalPersonName: profile.legalPersonName || '',
        legalPersonIdNumber: '',
        registeredCapitalYuan: profile.registeredCapitalCents ? (profile.registeredCapitalCents / 100).toFixed(2) : '',
        establishmentDate: profile.establishmentDate || '',
        businessAddressProvince: address?.province || '',
        businessAddressCity: address?.city || '',
        businessAddressDistrict: address?.district || '',
        businessAddressDetail: address?.address || '',
        contactPhone: profile.contactPhone || '',
        contactEmail: profile.contactEmail || '',
      }
    }
  } catch (error: unknown) {
    if (!isCurrentOrganization(orgId, version)) return
    merchantReadError.value = error instanceof Error ? error.message : '商家资料加载失败'
  }
}

async function loadMerchantAttachments(orgId: string, version: number): Promise<void> {
  const list = await grassland.listMerchantAttachments(orgId)
  if (list && isCurrentOrganization(orgId, version)) merchantAttachments.value = list
}

async function saveMerchantProfile(): Promise<void> {
  const orgId = props.orgId
  const version = organizationLoadVersion
  const address = {
    province: merchantForm.value.businessAddressProvince,
    city: merchantForm.value.businessAddressCity,
    district: merchantForm.value.businessAddressDistrict,
    address: merchantForm.value.businessAddressDetail,
  }
  const input = {
    legalName: merchantForm.value.legalName || undefined,
    unifiedSocialCreditCode: merchantForm.value.unifiedSocialCreditCode || undefined,
    businessType: merchantForm.value.businessType || undefined,
    legalPersonName: merchantForm.value.legalPersonName || undefined,
    legalPersonIdNumber: merchantForm.value.legalPersonIdNumber || undefined,
    registeredCapitalCents: merchantForm.value.registeredCapitalYuan
      ? Math.round(parseFloat(merchantForm.value.registeredCapitalYuan) * 100)
      : undefined,
    establishmentDate: merchantForm.value.establishmentDate || undefined,
    businessAddress: address.address ? address : undefined,
    contactPhone: merchantForm.value.contactPhone || undefined,
    contactEmail: merchantForm.value.contactEmail || undefined,
  }
  const result = merchantProfile.value
    ? await grassland.updateMerchantProfile(orgId, input)
    : await grassland.createMerchantProfile(orgId, input)
  if (result && isCurrentOrganization(orgId, version)) {
    merchantProfile.value = result
    emit('changed')
  }
}

async function submitMerchantProfile(): Promise<void> {
  const orgId = props.orgId
  const version = organizationLoadVersion
  const result = await grassland.submitMerchantProfile(orgId)
  if (result && isCurrentOrganization(orgId, version)) {
    merchantProfile.value = result
    emit('changed')
  }
}

async function handleFileUpload(event: Event, attachmentType: MerchantAttachmentType): Promise<void> {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  const orgId = props.orgId
  const version = organizationLoadVersion
  const result = await grassland.uploadMerchantAttachment(orgId, file, attachmentType)
  if (result && isCurrentOrganization(orgId, version)) {
    merchantAttachments.value = [...merchantAttachments.value, result]
    input.value = ''
  }
}

async function deleteAttachment(attachmentId: string): Promise<void> {
  const orgId = props.orgId
  const version = organizationLoadVersion
  const result = await grassland.deleteMerchantAttachment(orgId, attachmentId)
  if (result !== null && isCurrentOrganization(orgId, version)) {
    merchantAttachments.value = merchantAttachments.value.filter((a) => a.id !== attachmentId)
  }
}

// 收款账户
async function loadWithdrawalAccounts(orgId: string, version: number): Promise<void> {
  const list = await grassland.listWithdrawalAccounts(orgId)
  if (list && isCurrentOrganization(orgId, version)) withdrawalAccounts.value = list
}

async function createWithdrawalAccount(): Promise<void> {
  const orgId = props.orgId
  const version = organizationLoadVersion
  const result = await grassland.createWithdrawalAccount(orgId, {
    accountType: accountForm.value.accountType,
    accountName: accountForm.value.accountName,
    accountNumber: accountForm.value.accountNumber,
    bankName: accountForm.value.bankName || undefined,
    branchName: accountForm.value.branchName || undefined,
  })
  if (result && isCurrentOrganization(orgId, version)) {
    withdrawalAccounts.value = [...withdrawalAccounts.value, result]
    accountForm.value = {
      accountType: 'bank_card',
      accountName: '',
      accountNumber: '',
      bankName: '',
      branchName: '',
    }
  }
}

async function submitWithdrawalAccount(accountId: string): Promise<void> {
  const orgId = props.orgId
  const version = organizationLoadVersion
  const result = await grassland.submitWithdrawalAccount(orgId, accountId)
  if (result && isCurrentOrganization(orgId, version)) {
    withdrawalAccounts.value = withdrawalAccounts.value.map((account) =>
      account.id === accountId ? result : account)
    emit('changed')
  }
}

async function setDefaultAccount(accountId: string): Promise<void> {
  const orgId = props.orgId
  const version = organizationLoadVersion
  const result = await grassland.setDefaultWithdrawalAccount(orgId, accountId)
  if (result && isCurrentOrganization(orgId, version)) {
    withdrawalAccounts.value = withdrawalAccounts.value.map((a) => ({
      ...a,
      isDefault: a.id === accountId,
    }))
  }
}

async function deleteWithdrawalAccount(accountId: string): Promise<void> {
  const orgId = props.orgId
  const version = organizationLoadVersion
  const result = await grassland.deleteWithdrawalAccount(orgId, accountId)
  if (result !== null && isCurrentOrganization(orgId, version)) {
    withdrawalAccounts.value = withdrawalAccounts.value.filter((a) => a.id !== accountId)
  }
}

// 门店资料
async function loadStores(orgId: string, version: number): Promise<void> {
  if (Array.isArray(props.stores)) {
    if (isCurrentOrganization(orgId, version)) {
      storeOptions.value = [...props.stores]
      if (storeOptions.value.length > 0 && !selectedStoreId.value) {
        selectedStoreId.value = storeOptions.value[0].id
      }
    }
    return
  }
  const list = await grassland.listStores(orgId)
  if (list && isCurrentOrganization(orgId, version)) {
    storeOptions.value = list
    if (list.length > 0 && !selectedStoreId.value) {
      selectedStoreId.value = list[0].id
    }
  }
}

async function loadStoreProfile(): Promise<void> {
  if (!selectedStoreId.value) return
  const orgId = props.orgId
  const version = organizationLoadVersion
  const storeId = selectedStoreId.value
  const operationVersion = ++storeOperationVersion
  storeReadError.value = ''
  storeProfileLoaded.value = false
  storeProfile.value = null
  storeForm.value = emptyStoreForm()
  try {
    const profile = await grassland.getStoreProfile(orgId, storeId)
    if (!isCurrentStoreOperation(orgId, version, storeId, operationVersion)) return
    storeProfileLoaded.value = true
    if (profile) {
      storeProfile.value = profile
      const address = parseAddress(profile.address)
      storeForm.value = {
        addressProvince: address?.province || '',
        addressCity: address?.city || '',
        addressDistrict: address?.district || '',
        addressDetail: address?.address || '',
        phone: profile.phone || '',
        description: profile.description || '',
        categories: (profile.categories ?? []).join('\n'),
        signatureItems: (profile.signatureItems ?? []).join('\n'),
        sellingPoints: (profile.sellingPoints ?? []).join('\n'),
        mustEmphasize: (profile.mustEmphasize ?? []).join('\n'),
        forbiddenPhrases: (profile.forbiddenPhrases ?? []).join('\n'),
        allowedTags: (profile.allowedTags ?? []).join('\n'),
        brandTone: profile.brandTone || '',
        priceRange: profile.priceRange || '',
        averageSpendYuan: profile.averageSpendCents == null
          ? ''
          : String(profile.averageSpendCents / 100),
        visitNotes: profile.visitNotes || '',
      }
    }
  } catch (error: unknown) {
    if (!isCurrentStoreOperation(orgId, version, storeId, operationVersion)) return
    storeReadError.value = error instanceof Error ? error.message : '门店资料加载失败'
  }
}

async function saveStoreProfile(): Promise<void> {
  if (!selectedStoreId.value) return
  const orgId = props.orgId
  const version = organizationLoadVersion
  const storeId = selectedStoreId.value
  const operationVersion = ++storeOperationVersion
  const address = {
    province: storeForm.value.addressProvince,
    city: storeForm.value.addressCity,
    district: storeForm.value.addressDistrict,
    address: storeForm.value.addressDetail,
  }
  const result = await grassland.createStoreProfile(orgId, storeId, {
    address: Object.values(address).some(Boolean) ? JSON.stringify(address) : undefined,
    phone: storeForm.value.phone || undefined,
    description: storeForm.value.description || undefined,
    // 任务书 #24：营销字段整份覆盖（后端空数组 = 清空），列表按换行拆行。
    categories: storeFormLines(storeForm.value.categories),
    signatureItems: storeFormLines(storeForm.value.signatureItems),
    sellingPoints: storeFormLines(storeForm.value.sellingPoints),
    mustEmphasize: storeFormLines(storeForm.value.mustEmphasize),
    forbiddenPhrases: storeFormLines(storeForm.value.forbiddenPhrases),
    allowedTags: storeFormLines(storeForm.value.allowedTags),
    brandTone: storeForm.value.brandTone || undefined,
    priceRange: storeForm.value.priceRange || undefined,
    averageSpendCents: averageSpendToCents(storeForm.value.averageSpendYuan),
    visitNotes: storeForm.value.visitNotes || undefined,
  })
  if (result && isCurrentStoreOperation(orgId, version, storeId, operationVersion)) {
    storeProfile.value = result
    emit('changed')
  }
}

async function submitStoreProfile(): Promise<void> {
  if (!selectedStoreId.value || !canSubmitStore.value) return
  const orgId = props.orgId
  const version = organizationLoadVersion
  const storeId = selectedStoreId.value
  const operationVersion = ++storeOperationVersion
  const result = await grassland.submitStoreProfile(orgId, storeId)
  if (result && isCurrentStoreOperation(orgId, version, storeId, operationVersion)) {
    storeProfile.value = result
    emit('changed')
  }
}

watch(selectedStoreId, () => {
  storeProfile.value = null
  storeProfileLoaded.value = false
  storeForm.value = emptyStoreForm()
  void loadStoreProfile()
})

function resetOrganizationState(): void {
  storeOperationVersion += 1
  activeTab.value = props.storeOnly ? 'store' : 'merchant'
  merchantProfile.value = null
  merchantAttachments.value = []
  merchantReadError.value = ''
  merchantProfileLoaded.value = false
  merchantForm.value = {
    legalName: '', unifiedSocialCreditCode: '', businessType: '', legalPersonName: '',
    legalPersonIdNumber: '', registeredCapitalYuan: '', establishmentDate: '',
    businessAddressProvince: '', businessAddressCity: '', businessAddressDistrict: '',
    businessAddressDetail: '', contactPhone: '', contactEmail: '',
  }
  withdrawalAccounts.value = []
  accountForm.value = {
    accountType: 'bank_card', accountName: '', accountNumber: '', bankName: '', branchName: '',
  }
  storeOptions.value = []
  selectedStoreId.value = ''
  storeProfile.value = null
  storeReadError.value = ''
  storeProfileLoaded.value = false
  storeForm.value = emptyStoreForm()
}

// 门店列表 prop 变化时同步下拉（新建门店后不刷新页面即可见）
watch(() => props.stores, (next) => {
  if (Array.isArray(next)) {
    storeOptions.value = [...next]
    if (storeOptions.value.length > 0 && !selectedStoreId.value) {
      selectedStoreId.value = storeOptions.value[0].id
    }
  }
}, { deep: true })

watch(() => props.orgId, (orgId) => {
  const version = ++organizationLoadVersion
  resetOrganizationState()
  if (props.storeOnly) {
    // 独立门店经理：不触碰组织级商家资料/收款账户端点（403），只载入门店列表与资料。
    void loadStores(orgId, version)
    return
  }
  void Promise.all([
    loadMerchantProfile(orgId, version),
    loadMerchantAttachments(orgId, version),
    loadWithdrawalAccounts(orgId, version),
    loadStores(orgId, version),
  ])
}, { immediate: true })

/**
 * 认证摘要冒泡（概览节的「认证」卡）。
 *
 * storeOnly 模式不拉组织级资料（会 403），merchantStatus 自然恒为 null——
 * 概览节在该模式下也不渲染，两侧一致。
 */
watch(
  (): OrgKybSummary => ({
    merchantStatus: merchantProfile.value?.status ?? null,
    // Array.isArray 守卫：load 只判 truthy，上游给非数组时这里不能连带崩掉整卡
    // （卡身用 v-for 能容忍，`.filter` 不能）。
    approvedWithdrawalCount: Array.isArray(withdrawalAccounts.value)
      ? withdrawalAccounts.value.filter((item) => item.status === 'approved').length
      : 0,
  }),
  (summary) => emit('summary', summary),
  { immediate: true },
)
</script>

<template>
  <div class="merchant-kyb-card">
    <h3>{{ storeOnly ? '门店 KYB 资料' : '商家 KYB 资料' }}</h3>

    <!-- 标签切换 -->
    <div class="kyb-tabs">
      <button
        v-if="!storeOnly"
        type="button"
        :class="{ active: activeTab === 'merchant' }"
        @click="activeTab = 'merchant'"
      >商家资料</button>
      <button
        v-if="!storeOnly"
        type="button"
        :class="{ active: activeTab === 'withdrawal' }"
        @click="activeTab = 'withdrawal'"
      >收款账户</button>
      <button
        type="button"
        :class="{ active: activeTab === 'store' }"
        @click="activeTab = 'store'"
      >门店资料</button>
    </div>

    <p v-if="activeTab === 'merchant' && merchantReadError" class="error-message" role="alert">
      {{ merchantReadError }}
    </p>
    <p v-if="activeTab === 'store' && storeReadError" class="error-message" role="alert">
      {{ storeReadError }}
    </p>

    <!-- 商家资料 -->
    <div v-if="activeTab === 'merchant'" class="kyb-section">
      <div v-if="merchantProfile" class="kyb-status">
        状态：<span :class="`status-${merchantProfile.status}`">
          {{ statusLabels[merchantProfile.status] || merchantProfile.status }}
        </span>
        <span v-if="merchantProfile.reviewNote" class="review-note">
          （审核意见：{{ merchantProfile.reviewNote }}）
        </span>
      </div>

      <form class="kyb-form" @submit.prevent>
        <div class="form-row">
          <label>企业名称 <input v-model="merchantForm.legalName" placeholder="请输入企业名称" /></label>
          <label>统一社会信用代码 <input v-model="merchantForm.unifiedSocialCreditCode" placeholder="请输入统一社会信用代码" /></label>
        </div>
        <div class="form-row">
          <label>行业类型 <input v-model="merchantForm.businessType" placeholder="请输入行业类型" /></label>
          <label>注册资本（元） <input v-model.number="merchantForm.registeredCapitalYuan" type="number" placeholder="请输入注册资本" /></label>
        </div>
        <div class="form-row">
          <label>法人姓名 <input v-model="merchantForm.legalPersonName" placeholder="请输入法人姓名" /></label>
          <label>法人身份证号
            <input v-model="merchantForm.legalPersonIdNumber" placeholder="请输入法人身份证号" />
            <span v-if="merchantProfile?.legalPersonIdNumberMasked" class="masked-value">
              已保存证件：{{ merchantProfile.legalPersonIdNumberMasked }}
            </span>
          </label>
        </div>
        <div class="form-row">
          <label>成立日期 <input v-model="merchantForm.establishmentDate" type="date" /></label>
          <label>联系电话 <input v-model="merchantForm.contactPhone" placeholder="请输入联系电话" /></label>
        </div>
        <div class="form-row">
          <label>联系邮箱 <input v-model="merchantForm.contactEmail" type="email" placeholder="请输入联系邮箱" /></label>
        </div>
        <div class="form-row">
          <label>企业地址</label>
          <div class="address-inputs">
            <input v-model="merchantForm.businessAddressProvince" placeholder="省份" />
            <input v-model="merchantForm.businessAddressCity" placeholder="城市" />
            <input v-model="merchantForm.businessAddressDistrict" placeholder="区县" />
            <input v-model="merchantForm.businessAddressDetail" placeholder="详细地址" class="full-width" />
          </div>
        </div>

        <div class="form-actions">
          <button
            type="button"
            :disabled="grassland.loading.value || !canEditMerchant"
            @click="saveMerchantProfile"
          >保存资料</button>
          <button
            type="button"
            :disabled="grassland.loading.value || !canSubmitMerchant
              || !merchantProfile || !['draft', 'rejected'].includes(merchantProfile.status)"
            @click="submitMerchantProfile"
          >提交审核</button>
        </div>
      </form>

      <!-- 附件管理 -->
      <div class="attachments-section">
        <h4>附件管理</h4>
        <div class="attachment-list">
          <div v-for="att in merchantAttachments" :key="att.id" class="attachment-item">
            <span class="attachment-type">{{ att.attachmentType }}</span>
            <span class="attachment-info">
              {{ att.mimeType }} · {{ att.sizeBytes ? `${(att.sizeBytes / 1024).toFixed(1)} KB` : '—' }}
            </span>
            <button
              type="button"
              :disabled="grassland.loading.value || !canEditAttachment(att.attachmentType)"
              @click="deleteAttachment(att.id)"
            >删除</button>
          </div>
        </div>
        <div class="attachment-upload">
          <label>
            营业执照
            <input
              type="file"
              accept="image/*,.pdf"
              :disabled="grassland.loading.value || !canEditMerchant"
              @change="(e) => handleFileUpload(e, 'business_license')"
            />
          </label>
          <label>
            法人身份证（正面）
            <input
              type="file"
              accept="image/*,.pdf"
              :disabled="grassland.loading.value || !canEditMerchant"
              @change="(e) => handleFileUpload(e, 'legal_person_id_front')"
            />
          </label>
          <label>
            法人身份证（背面）
            <input
              type="file"
              accept="image/*,.pdf"
              :disabled="grassland.loading.value || !canEditMerchant"
              @change="(e) => handleFileUpload(e, 'legal_person_id_back')"
            />
          </label>
          <label>
            门店照片
            <input
              type="file"
              accept="image/*,.pdf"
              :disabled="grassland.loading.value || !canEditMerchant"
              @change="(e) => handleFileUpload(e, 'store_photo')"
            />
          </label>
          <label>
            行业许可证
            <input
              type="file"
              accept="image/*,.pdf"
              :disabled="grassland.loading.value || !canEditPermissionSupplements"
              @change="(e) => handleFileUpload(e, 'industry_license')"
            />
          </label>
          <label>
            财务资质
            <input
              type="file"
              accept="image/*,.pdf"
              :disabled="grassland.loading.value || !canEditPermissionSupplements"
              @change="(e) => handleFileUpload(e, 'financial_qualification')"
            />
          </label>
          <label>
            其他附件
            <input
              type="file"
              accept="image/*,.pdf"
              :disabled="grassland.loading.value || !canEditMerchant"
              @change="(e) => handleFileUpload(e, 'other')"
            />
          </label>
        </div>
      </div>
    </div>

    <!-- 收款账户 -->
    <div v-else-if="activeTab === 'withdrawal'" class="kyb-section">
      <div class="account-list">
        <div v-for="acc in withdrawalAccounts" :key="acc.id" class="account-item">
          <div class="account-info">
            <span class="account-type">{{ accountTypeLabels[acc.accountType] || acc.accountType }}</span>
            <span class="account-number">{{ acc.accountNumberMasked }}</span>
            <span v-if="acc.bankName" class="bank-name">{{ acc.bankName }}</span>
            <span v-if="acc.branchName" class="branch-name">{{ acc.branchName }}</span>
            <span :class="`status-${acc.status}`">
              {{ statusLabels[acc.status] || acc.status }}
            </span>
            <span v-if="acc.isDefault" class="default-badge">默认</span>
            <span v-if="acc.reviewNote" class="review-note">{{ acc.reviewNote }}</span>
          </div>
          <div class="account-actions">
            <button
              v-if="acc.status === 'pending' || acc.status === 'rejected'"
              type="button"
              :disabled="grassland.loading.value"
              @click="submitWithdrawalAccount(acc.id)"
            >提交审核</button>
            <button
              v-if="acc.status === 'approved' && !acc.isDefault"
              type="button"
              :disabled="grassland.loading.value"
              @click="setDefaultAccount(acc.id)"
            >设为默认</button>
            <button
              type="button"
              :disabled="grassland.loading.value"
              @click="deleteWithdrawalAccount(acc.id)"
            >删除</button>
          </div>
        </div>
      </div>

      <form class="kyb-form account-form" @submit.prevent>
        <h4>添加收款账户</h4>
        <div class="form-row">
          <label>账户类型
            <select v-model="accountForm.accountType">
              <option value="bank_card">银行卡</option>
              <option value="alipay">支付宝</option>
              <option value="wechat">微信</option>
            </select>
          </label>
          <label>账户名称 <input v-model="accountForm.accountName" placeholder="请输入账户名称" /></label>
        </div>
        <div v-if="accountForm.accountType === 'bank_card'" class="form-row">
          <label>银行名称 <input v-model="accountForm.bankName" placeholder="请输入银行名称" /></label>
          <label>开户行 <input v-model="accountForm.branchName" placeholder="请输入开户行" /></label>
        </div>
        <div class="form-row">
          <label>账号/卡号 <input v-model="accountForm.accountNumber" placeholder="请输入账号或卡号" /></label>
        </div>
        <div class="form-actions">
          <button
            type="button"
            :disabled="grassland.loading.value || !accountForm.accountName || !accountForm.accountNumber"
            @click="createWithdrawalAccount"
          >添加账户</button>
        </div>
      </form>
    </div>

    <!-- 门店资料 -->
    <div v-else-if="activeTab === 'store'" class="kyb-section">
      <div class="store-selector">
        <label>选择门店
          <select v-model="selectedStoreId">
            <option value="" disabled>请选择门店</option>
            <option v-for="s in storeOptions" :key="s.id" :value="s.id">{{ s.name }}</option>
          </select>
        </label>
      </div>

      <div v-if="selectedStoreId && storeProfile" class="store-status">
        状态：<span :class="`status-${storeProfile.status}`">
          {{ statusLabels[storeProfile.status] || storeProfile.status }}
        </span>
        <span v-if="storeProfile.reviewNote" class="review-note">
          （审核意见：{{ storeProfile.reviewNote }}）
        </span>
      </div>

      <template v-if="selectedStoreId">
      <form class="kyb-form" @submit.prevent>
        <div class="form-row">
          <label>门店地址</label>
          <div class="address-inputs">
            <input v-model="storeForm.addressProvince" placeholder="省份" />
            <input v-model="storeForm.addressCity" placeholder="城市" />
            <input v-model="storeForm.addressDistrict" placeholder="区县" />
            <input v-model="storeForm.addressDetail" placeholder="详细地址" class="full-width" />
          </div>
        </div>
        <div class="form-row">
          <label>联系电话 <input v-model="storeForm.phone" placeholder="请输入联系电话" /></label>
        </div>
        <div class="form-row">
          <label>门店描述 <textarea v-model="storeForm.description" placeholder="请输入门店描述（选填）" rows="3" /></label>
        </div>
        <!-- 任务书 #24：PRD §2.1 营销字段（列表类每行一项） -->
        <div class="form-row">
          <label>主营品类 <textarea v-model="storeForm.categories" placeholder="每行一项，如：火锅（选填）" rows="2" /></label>
        </div>
        <div class="form-row">
          <label>特色产品/服务 <textarea v-model="storeForm.signatureItems" placeholder="每行一项（选填）" rows="2" /></label>
        </div>
        <div class="form-row">
          <label>推荐卖点 <textarea v-model="storeForm.sellingPoints" placeholder="每行一项（选填）" rows="2" /></label>
        </div>
        <div class="form-row">
          <label>必须强调 <textarea v-model="storeForm.mustEmphasize" placeholder="AI 创作必须逐条体现，每行一项（选填）" rows="2" /></label>
        </div>
        <div class="form-row">
          <label>禁止表达 <textarea v-model="storeForm.forbiddenPhrases" placeholder="AI 创作严禁出现，每行一项（选填）" rows="2" /></label>
        </div>
        <div class="form-row">
          <label>可使用标签 <textarea v-model="storeForm.allowedTags" placeholder="每行一个标签，如：#探店（选填）" rows="2" /></label>
        </div>
        <div class="form-row">
          <label>品牌语气 <textarea v-model="storeForm.brandTone" placeholder="如：温暖亲切（选填，≤500 字）" rows="2" /></label>
        </div>
        <div class="form-row">
          <label>价格区间 <input v-model="storeForm.priceRange" placeholder="如：¥30–¥80（选填）" /></label>
        </div>
        <div class="form-row">
          <label>人均消费（元） <input v-model="storeForm.averageSpendYuan" type="number" min="0" step="0.01" placeholder="如：65（选填）" /></label>
        </div>
        <div class="form-row">
          <label>交通/停车/预约/到店注意 <textarea v-model="storeForm.visitNotes" placeholder="选填，≤1000 字" rows="3" /></label>
        </div>

        <div class="form-actions">
          <button
            type="button"
            :disabled="grassland.loading.value || !storeProfileLoaded || Boolean(storeReadError)
              || !canEditStore || !storeForm.addressDetail"
            @click="saveStoreProfile"
          >保存资料</button>
          <button
            type="button"
            :disabled="grassland.loading.value || !canSubmitStore"
            @click="submitStoreProfile"
          >提交审核</button>
        </div>
      </form>

      <!-- 任务书 #42：门店媒体库（不进 KYB 状态机，D8：绑定/解绑不触发资料 draft 重置） -->
      <StoreMediaManager :org-id="orgId" :store-id="selectedStoreId" />
      </template>

      <div v-else class="empty-hint">
        请先选择一个门店
      </div>
    </div>
  </div>
</template>

<style scoped>
.merchant-kyb-card {
  width: 100%;
}

.merchant-kyb-card h3 {
  margin: 0 0 16px 0;
  font-size: 16px;
  font-weight: 600;
}

.kyb-tabs {
  display: flex;
  gap: 8px;
  margin-bottom: 16px;
  border-bottom: 1px solid var(--color-border);
}

.kyb-tabs button {
  padding: 8px 16px;
  border: none;
  background: none;
  border-bottom: 2px solid transparent;
  cursor: pointer;
  transition: all 0.2s;
}

.kyb-tabs button:hover {
  background: var(--surface-muted);
}

.kyb-tabs button.active {
  border-bottom-color: var(--color-accent);
  color: var(--color-accent);
  font-weight: 500;
}

.kyb-section {
  padding: 16px 0;
}

.error-message {
  margin: 0 0 12px;
  color: var(--color-danger);
  font-size: 13px;
}

.kyb-status {
  padding: 8px 12px;
  margin-bottom: 16px;
  background: var(--surface-muted);
  border-radius: var(--radius-sm);
  font-size: 14px;
}

.status-draft {
  color: var(--color-text-muted);
}

.status-pending,
.status-under_review {
  color: var(--color-warning);
}

.status-approved {
  color: var(--color-success);
}

.status-rejected {
  color: var(--color-danger);
}

.review-note {
  margin-left: 8px;
  color: var(--color-text-muted);
}

.kyb-form {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.form-row {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 12px;
}

.form-row label {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 13px;
  color: var(--color-text-secondary);
}

.form-row input,
.form-row select,
.form-row textarea {
  padding: 8px 12px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  font-size: 14px;
}

.form-row input:focus,
.form-row select:focus,
.form-row textarea:focus {
  outline: none;
  border-color: var(--color-accent);
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--color-accent) 10%, transparent);
}

.address-inputs {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
}

.address-inputs .full-width {
  grid-column: 1 / -1;
}

.form-actions {
  display: flex;
  gap: 8px;
  padding-top: 8px;
}

.form-actions button {
  padding: 8px 16px;
  border: 1px solid var(--color-border);
  background: white;
  border-radius: var(--radius-sm);
  cursor: pointer;
  transition: all 0.2s;
}

.form-actions button:hover:not(:disabled) {
  background: var(--surface-muted);
  border-color: var(--color-text-muted);
}

.form-actions button:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.attachments-section {
  margin-top: 24px;
  padding-top: 16px;
  border-top: 1px solid var(--color-border);
}

.attachments-section h4 {
  margin: 0 0 12px 0;
  font-size: 14px;
  font-weight: 500;
}

.attachment-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-bottom: 12px;
}

.attachment-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 12px;
  background: var(--surface-muted);
  border-radius: var(--radius-sm);
  font-size: 13px;
}

.attachment-type {
  font-weight: 500;
  color: var(--color-text-secondary);
}

.attachment-info {
  color: var(--color-text-muted);
}

.attachment-upload {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 12px;
}

.attachment-upload label {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 13px;
  color: var(--color-text-secondary);
}

.attachment-upload input[type="file"] {
  padding: 6px;
  border: 1px dashed var(--color-border);
  border-radius: var(--radius-sm);
  cursor: pointer;
}

.account-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-bottom: 24px;
}

.account-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px;
  background: var(--surface-muted);
  border-radius: var(--radius-md);
}

.account-info {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  font-size: 14px;
}

.account-type {
  font-weight: 500;
}

.account-number {
  font-family: monospace;
}

.default-badge {
  padding: 2px 8px;
  background: color-mix(in srgb, var(--color-info) 12%, transparent);
  color: var(--color-info);
  border-radius: var(--radius-xs);
  font-size: 12px;
}

.account-actions {
  display: flex;
  gap: 8px;
}

.account-actions button {
  padding: 6px 12px;
  font-size: 13px;
}

.store-selector {
  margin-bottom: 16px;
}

.store-selector label {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 13px;
}

.store-selector select {
  padding: 8px 12px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
}

.store-status {
  padding: 8px 12px;
  margin-bottom: 16px;
  background: var(--surface-muted);
  border-radius: var(--radius-sm);
  font-size: 14px;
}

.empty-hint {
  padding: 24px;
  text-align: center;
  color: var(--color-text-muted);
}
</style>
