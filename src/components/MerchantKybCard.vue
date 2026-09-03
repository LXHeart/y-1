<script setup lang="ts">
import { ref, toRef, watch } from 'vue'
import { useGrassland } from '../composables/useGrassland'
import { useMerchantProfileDomain } from './kyb/useMerchantProfileDomain'
import { useWithdrawalDomain } from './kyb/useWithdrawalDomain'
import { useStoreDomain } from './kyb/useStoreDomain'
import { statusLabels, accountTypeLabels } from './kyb/kyb-shared'
import StoreMediaManager from './StoreMediaManager.vue'
import type { OrgKybSummary } from '../types/grassland'

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

// 任务书 #68 卡 F：script 三域拆分至 ./kyb/ 三个 composable。useGrassland() 不是单例
// （每次调用各自新建 loading/error ref），全卡按钮的 :disabled 共享同一 loading，
// 因此实例只在父文件创建一次、传入三域。模板绑定名经解构别名保持原样（DOM 零变更）。
const grassland = useGrassland()
const merchantDomain = useMerchantProfileDomain({
  orgId: toRef(props, 'orgId'),
  grassland,
  onChanged: () => emit('changed'),
})
const withdrawalDomain = useWithdrawalDomain({
  orgId: toRef(props, 'orgId'),
  grassland,
  onChanged: () => emit('changed'),
})
const storeDomain = useStoreDomain({
  orgId: toRef(props, 'orgId'),
  grassland,
  storesProp: toRef(props, 'stores'),
  onChanged: () => emit('changed'),
})

const {
  profile: merchantProfile,
  attachments: merchantAttachments,
  form: merchantForm,
  readError: merchantReadError,
  fieldErrors: merchantFieldErrors,
  businessTypeOptions,
  industryOptions,
  provinceOptions: merchantProvinceOptions,
  cityOptions: merchantCityOptions,
  districtOptions: merchantDistrictOptions,
  canSubmit: canSubmitMerchant,
  canEdit: canEditMerchant,
  canEditPermissionSupplements,
  canEditAttachment,
  validateField: validateMerchantField,
  clearFieldError: clearMerchantFieldError,
  onProvinceChange: onMerchantProvinceChange,
  onCityChange: onMerchantCityChange,
  save: saveMerchantProfile,
  submit: submitMerchantProfile,
  handleFileUpload,
  deleteAttachment,
} = merchantDomain

const {
  accounts: withdrawalAccounts,
  form: accountForm,
  createAccount: createWithdrawalAccount,
  submitAccount: submitWithdrawalAccount,
  setDefault: setDefaultAccount,
  deleteAccount: deleteWithdrawalAccount,
} = withdrawalDomain

const {
  options: storeOptions,
  selectedId: selectedStoreId,
  profile: storeProfile,
  form: storeForm,
  readError: storeReadError,
  profileLoaded: storeProfileLoaded,
  fieldErrors: storeFieldErrors,
  provinceOptions: storeProvinceOptions,
  cityOptions: storeCityOptions,
  districtOptions: storeDistrictOptions,
  canEdit: canEditStore,
  canSubmit: canSubmitStore,
  validateField: validateStoreField,
  clearFieldError: clearStoreFieldError,
  onProvinceChange: onStoreProvinceChange,
  onCityChange: onStoreCityChange,
  save: saveStoreProfile,
  submit: submitStoreProfile,
} = storeDomain

// 当前标签页
type KybTab = 'merchant' | 'withdrawal' | 'store'
const activeTab = ref<KybTab>(props.storeOnly ? 'store' : 'merchant')

watch(() => props.orgId, (orgId) => {
  // 各域 load() 自持版本计数并丢弃过期异步写（等价原先单版本四路共用的守卫语义）。
  activeTab.value = props.storeOnly ? 'store' : 'merchant'
  merchantDomain.reset()
  withdrawalDomain.reset()
  storeDomain.reset()
  if (props.storeOnly) {
    // 独立门店经理：不触碰组织级商家资料/收款账户端点（403），只载入门店列表与资料。
    void storeDomain.loadStores(orgId)
    return
  }
  void Promise.all([
    merchantDomain.load(orgId),
    withdrawalDomain.load(orgId),
    storeDomain.loadStores(orgId),
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
    merchantStatus: merchantDomain.profile.value?.status ?? null,
    approvedWithdrawalCount: withdrawalDomain.approvedWithdrawalCount.value,
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
          <label for="merchant-business-type">企业类型
            <select id="merchant-business-type" v-model="merchantForm.businessType" name="businessType">
              <option value="">请选择企业类型</option>
              <option v-for="option in businessTypeOptions" :key="option.value" :value="option.value">
                {{ option.label }}
              </option>
            </select>
          </label>
          <label for="merchant-industry">行业类型
            <select id="merchant-industry" v-model="merchantForm.industry" name="industry">
              <option value="">请选择行业类型</option>
              <option v-for="option in industryOptions" :key="option.value" :value="option.value">
                {{ option.label }}
              </option>
            </select>
          </label>
        </div>
        <div class="form-row">
          <label>注册资本（元） <input v-model.number="merchantForm.registeredCapitalYuan" type="number" placeholder="请输入注册资本" /></label>
          <label>成立日期 <input v-model="merchantForm.establishmentDate" type="date" /></label>
        </div>
        <div class="form-row">
          <label>法人姓名 <input v-model="merchantForm.legalPersonName" placeholder="请输入法人姓名" /></label>
          <label for="merchant-id-number">法人身份证号
            <input
              id="merchant-id-number"
              v-model.trim="merchantForm.legalPersonIdNumber"
              name="legalPersonIdNumber"
              inputmode="text"
              autocomplete="off"
              maxlength="18"
              placeholder="请输入法人身份证号"
              :aria-invalid="Boolean(merchantFieldErrors.legalPersonIdNumber)"
              aria-describedby="merchant-id-number-error"
              @input="clearMerchantFieldError('legalPersonIdNumber')"
              @blur="validateMerchantField('legalPersonIdNumber')"
            />
            <span v-if="merchantProfile?.legalPersonIdNumberMasked" class="masked-value">
              已保存证件：{{ merchantProfile.legalPersonIdNumberMasked }}
            </span>
            <span v-if="merchantFieldErrors.legalPersonIdNumber" id="merchant-id-number-error" class="field-error" role="alert">
              {{ merchantFieldErrors.legalPersonIdNumber }}
            </span>
          </label>
        </div>
        <div class="form-row">
          <label for="merchant-contact-phone">联系电话
            <input
              id="merchant-contact-phone"
              v-model.trim="merchantForm.contactPhone"
              name="contactPhone"
              type="tel"
              inputmode="tel"
              autocomplete="tel"
              maxlength="32"
              placeholder="请输入联系电话"
              :aria-invalid="Boolean(merchantFieldErrors.contactPhone)"
              aria-describedby="merchant-contact-phone-error"
              @input="clearMerchantFieldError('contactPhone')"
              @blur="validateMerchantField('contactPhone')"
            />
            <span v-if="merchantFieldErrors.contactPhone" id="merchant-contact-phone-error" class="field-error" role="alert">
              {{ merchantFieldErrors.contactPhone }}
            </span>
          </label>
          <label for="merchant-contact-email">联系邮箱
            <input
              id="merchant-contact-email"
              v-model.trim="merchantForm.contactEmail"
              name="contactEmail"
              type="email"
              autocomplete="email"
              maxlength="254"
              placeholder="请输入联系邮箱"
              :aria-invalid="Boolean(merchantFieldErrors.contactEmail)"
              aria-describedby="merchant-contact-email-error"
              @input="clearMerchantFieldError('contactEmail')"
              @blur="validateMerchantField('contactEmail')"
            />
            <span v-if="merchantFieldErrors.contactEmail" id="merchant-contact-email-error" class="field-error" role="alert">
              {{ merchantFieldErrors.contactEmail }}
            </span>
          </label>
        </div>
        <div class="form-row">
          <label>企业地址</label>
          <div class="address-inputs">
            <select
              v-model="merchantForm.businessAddressProvince"
              name="businessAddressProvince"
              aria-label="省份"
              @change="onMerchantProvinceChange"
            >
              <option value="">请选择省份</option>
              <option v-for="province in merchantProvinceOptions" :key="province.value" :value="province.value">
                {{ province.label }}
              </option>
            </select>
            <select
              v-model="merchantForm.businessAddressCity"
              name="businessAddressCity"
              aria-label="城市"
              :disabled="!merchantForm.businessAddressProvince"
              @change="onMerchantCityChange"
            >
              <option value="">请选择城市</option>
              <option v-for="city in merchantCityOptions" :key="city.value" :value="city.value">
                {{ city.label }}
              </option>
            </select>
            <select
              v-model="merchantForm.businessAddressDistrict"
              name="businessAddressDistrict"
              aria-label="区县"
              :disabled="!merchantForm.businessAddressCity"
            >
              <option value="">请选择区县</option>
              <option v-for="district in merchantDistrictOptions" :key="district.value" :value="district.value">
                {{ district.label }}
              </option>
            </select>
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
            <select
              v-model="storeForm.addressProvince"
              name="storeAddressProvince"
              aria-label="门店省份"
              @change="onStoreProvinceChange"
            >
              <option value="">请选择省份</option>
              <option v-for="province in storeProvinceOptions" :key="province.value" :value="province.value">
                {{ province.label }}
              </option>
            </select>
            <select
              v-model="storeForm.addressCity"
              name="storeAddressCity"
              aria-label="门店城市"
              :disabled="!storeForm.addressProvince"
              @change="onStoreCityChange"
            >
              <option value="">请选择城市</option>
              <option v-for="city in storeCityOptions" :key="city.value" :value="city.value">
                {{ city.label }}
              </option>
            </select>
            <select
              v-model="storeForm.addressDistrict"
              name="storeAddressDistrict"
              aria-label="门店区县"
              :disabled="!storeForm.addressCity"
            >
              <option value="">请选择区县</option>
              <option v-for="district in storeDistrictOptions" :key="district.value" :value="district.value">
                {{ district.label }}
              </option>
            </select>
            <input v-model="storeForm.addressDetail" placeholder="详细地址" class="full-width" />
          </div>
        </div>
        <div class="form-row">
          <label for="store-contact-phone">联系电话
            <input
              id="store-contact-phone"
              v-model.trim="storeForm.phone"
              name="storePhone"
              type="tel"
              inputmode="tel"
              autocomplete="tel"
              maxlength="32"
              placeholder="请输入联系电话"
              :aria-invalid="Boolean(storeFieldErrors.phone)"
              aria-describedby="store-contact-phone-error"
              @input="clearStoreFieldError('phone')"
              @blur="validateStoreField('phone')"
            />
            <span v-if="storeFieldErrors.phone" id="store-contact-phone-error" class="field-error" role="alert">
              {{ storeFieldErrors.phone }}
            </span>
          </label>
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
  width: 100%;
  min-width: 0;
  padding: 8px 12px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--color-surface);
  color: var(--color-text);
  font-size: 14px;
}

.form-row input:focus,
.form-row select:focus,
.form-row textarea:focus {
  outline: none;
  border-color: var(--color-accent);
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--color-accent) 10%, transparent);
}

.form-row input[aria-invalid="true"],
.form-row select[aria-invalid="true"],
.form-row textarea[aria-invalid="true"] {
  border-color: var(--color-danger);
}

.form-row select:disabled {
  cursor: not-allowed;
  color: var(--color-text-muted);
  background: var(--surface-muted);
}

.field-error {
  color: var(--color-danger);
  font-size: var(--text-xs);
}

.address-inputs {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--space-xs);
  min-width: 0;
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
  background: var(--color-surface);
  color: var(--color-text);
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

@media (max-width: 768px) {
  .address-inputs {
    grid-template-columns: 1fr;
  }

  .address-inputs .full-width {
    grid-column: auto;
  }
}
</style>
