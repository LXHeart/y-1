<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useGrassland } from '../composables/useGrassland'
import type {
  MerchantProfile,
  MerchantAttachment,
  MerchantAttachmentType,
  WithdrawalAccount,
  StoreProfile,
} from '../types/grassland'

interface Props {
  orgId: string
}

const props = defineProps<Props>()
const emit = defineEmits<{
  changed: []
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
const activeTab = ref<KybTab>('merchant')

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
const stores = ref<{ id: string; name: string }[]>([])
const selectedStoreId = ref('')
const storeProfile = ref<StoreProfile | null>(null)
const storeForm = ref({
  addressProvince: '',
  addressCity: '',
  addressDistrict: '',
  addressDetail: '',
  phone: '',
  description: '',
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
  }
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
  const list = await grassland.listStores(orgId)
  if (list && isCurrentOrganization(orgId, version)) {
    stores.value = list
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
  activeTab.value = 'merchant'
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
  stores.value = []
  selectedStoreId.value = ''
  storeProfile.value = null
  storeReadError.value = ''
  storeProfileLoaded.value = false
  storeForm.value = emptyStoreForm()
}

watch(() => props.orgId, (orgId) => {
  const version = ++organizationLoadVersion
  resetOrganizationState()
  void Promise.all([
    loadMerchantProfile(orgId, version),
    loadMerchantAttachments(orgId, version),
    loadWithdrawalAccounts(orgId, version),
    loadStores(orgId, version),
  ])
}, { immediate: true })
</script>

<template>
  <div class="merchant-kyb-card">
    <h3>商家 KYB 资料</h3>

    <!-- 标签切换 -->
    <div class="kyb-tabs">
      <button
        type="button"
        :class="{ active: activeTab === 'merchant' }"
        @click="activeTab = 'merchant'"
      >商家资料</button>
      <button
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
            <option v-for="s in stores" :key="s.id" :value="s.id">{{ s.name }}</option>
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

      <form v-if="selectedStoreId" class="kyb-form" @submit.prevent>
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
  border-bottom: 1px solid #e5e7eb;
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
  background: #f3f4f6;
}

.kyb-tabs button.active {
  border-bottom-color: #3b82f6;
  color: #3b82f6;
  font-weight: 500;
}

.kyb-section {
  padding: 16px 0;
}

.error-message {
  margin: 0 0 12px;
  color: #b91c1c;
  font-size: 13px;
}

.kyb-status {
  padding: 8px 12px;
  margin-bottom: 16px;
  background: #f3f4f6;
  border-radius: 6px;
  font-size: 14px;
}

.status-draft {
  color: #6b7280;
}

.status-pending,
.status-under_review {
  color: #f59e0b;
}

.status-approved {
  color: #10b981;
}

.status-rejected {
  color: #ef4444;
}

.review-note {
  margin-left: 8px;
  color: #6b7280;
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
  color: #374151;
}

.form-row input,
.form-row select,
.form-row textarea {
  padding: 8px 12px;
  border: 1px solid #d1d5db;
  border-radius: 6px;
  font-size: 14px;
}

.form-row input:focus,
.form-row select:focus,
.form-row textarea:focus {
  outline: none;
  border-color: #3b82f6;
  box-shadow: 0 0 0 2px rgba(59, 130, 246, 0.1);
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
  border: 1px solid #d1d5db;
  background: white;
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.2s;
}

.form-actions button:hover:not(:disabled) {
  background: #f9fafb;
  border-color: #9ca3af;
}

.form-actions button:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.attachments-section {
  margin-top: 24px;
  padding-top: 16px;
  border-top: 1px solid #e5e7eb;
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
  background: #f9fafb;
  border-radius: 6px;
  font-size: 13px;
}

.attachment-type {
  font-weight: 500;
  color: #374151;
}

.attachment-info {
  color: #6b7280;
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
  color: #374151;
}

.attachment-upload input[type="file"] {
  padding: 6px;
  border: 1px dashed #d1d5db;
  border-radius: 6px;
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
  background: #f9fafb;
  border-radius: 8px;
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
  background: #dbeafe;
  color: #1d4ed8;
  border-radius: 4px;
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
  border: 1px solid #d1d5db;
  border-radius: 6px;
}

.store-status {
  padding: 8px 12px;
  margin-bottom: 16px;
  background: #f3f4f6;
  border-radius: 6px;
  font-size: 14px;
}

.empty-hint {
  padding: 24px;
  text-align: center;
  color: #9ca3af;
}
</style>
