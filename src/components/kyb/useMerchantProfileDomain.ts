import { computed, ref, type Ref } from 'vue'
import { useGrassland } from '../../composables/useGrassland'
import {
  validateChineseIdCard,
  validateEmail,
  validatePhone,
} from '../../lib/kyb-validation'
import {
  BUSINESS_TYPE_OPTIONS,
  INDUSTRY_OPTIONS,
  buildRegionCascade,
  optionsWithCurrentValue,
  parseAddress,
} from './kyb-shared'
import type {
  MerchantProfile,
  MerchantAttachment,
  MerchantAttachmentType,
  Industry,
} from '../../types/grassland'

/**
 * 任务书 #68 卡 F：商家资料域（自 MerchantKybCard script 迁出，逻辑逐字符保真）。
 *
 * ⚠ grassland 实例由父组件创建后传入——useGrassland() 不是单例（每次调用各自新建
 * loading/error ref），各域自行调用会让「任一操作禁用全部按钮」的现状行为漂移。
 */
type GrasslandApi = ReturnType<typeof useGrassland>

interface MerchantProfileDomainDeps {
  orgId: Ref<string>
  grassland: GrasslandApi
  onChanged: () => void
}

export type MerchantValidationField = 'legalPersonIdNumber' | 'contactPhone' | 'contactEmail'

export function useMerchantProfileDomain(deps: MerchantProfileDomainDeps) {
  const grassland = deps.grassland

  const readError = ref('')
  const profileLoaded = ref(false)
  let loadVersion = 0

  const profile = ref<MerchantProfile | null>(null)
  const attachments = ref<MerchantAttachment[]>([])
  const form = ref({
    legalName: '',
    unifiedSocialCreditCode: '',
    industry: '',
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

  const fieldErrors = ref<Partial<Record<MerchantValidationField, string>>>({})

  const businessTypeOptions = computed(() => {
    const current = form.value.businessType
    return optionsWithCurrentValue(BUSINESS_TYPE_OPTIONS, current)
  })

  const industryOptions = computed(() => {
    const current = form.value.industry
    return optionsWithCurrentValue(INDUSTRY_OPTIONS, current)
  })

  const { provinceOptions, cityOptions, districtOptions } = buildRegionCascade(
    () => form.value.businessAddressProvince,
    () => form.value.businessAddressCity,
    () => form.value.businessAddressDistrict,
  )

  function idError(): string | null {
    // 已保存证件只回掩码；空输入代表沿用原证件，不应要求用户再次录入明文。
    if (!form.value.legalPersonIdNumber && profile.value?.legalPersonIdNumberMasked) return null
    return validateChineseIdCard(form.value.legalPersonIdNumber)
  }

  function phoneError(): string | null {
    return validatePhone(form.value.contactPhone)
  }

  function emailError(): string | null {
    return validateEmail(form.value.contactEmail)
  }

  const canSubmit = computed(() => {
    const f = form.value
    const hasIdNumber = Boolean(f.legalPersonIdNumber || profile.value?.legalPersonIdNumberMasked)
    return Boolean(
      f.legalName && f.unifiedSocialCreditCode && f.legalPersonName && hasIdNumber
      && !idError() && !phoneError() && !emailError(),
    )
  })

  const canEdit = computed(() => profileLoaded.value && !readError.value
    && (!profile.value
      || profile.value.status === 'draft'
      || profile.value.status === 'rejected'))

  const canEditPermissionSupplements = computed(() => profileLoaded.value && !readError.value
    && (!profile.value || !['pending', 'under_review'].includes(profile.value.status)))

  function canEditAttachment(attachmentType: MerchantAttachmentType): boolean {
    return attachmentType === 'industry_license' || attachmentType === 'financial_qualification'
      ? canEditPermissionSupplements.value
      : canEdit.value
  }

  function setFieldError(field: MerchantValidationField, message: string | null): void {
    const next = { ...fieldErrors.value }
    if (message) next[field] = message
    else delete next[field]
    fieldErrors.value = next
  }

  function validateField(field: MerchantValidationField): boolean {
    const error = field === 'legalPersonIdNumber'
      ? idError()
      : field === 'contactPhone' ? phoneError() : emailError()
    setFieldError(field, error)
    return !error
  }

  function validateForm(): boolean {
    const valid = (['legalPersonIdNumber', 'contactPhone', 'contactEmail'] as MerchantValidationField[])
      .map((field) => validateField(field)).every(Boolean)
    return valid
  }

  function clearFieldError(field: MerchantValidationField): void {
    if (fieldErrors.value[field]) setFieldError(field, null)
  }

  function onProvinceChange(): void {
    form.value.businessAddressCity = ''
    form.value.businessAddressDistrict = ''
  }

  function onCityChange(): void {
    form.value.businessAddressDistrict = ''
  }

  function isCurrentOrganization(orgId: string, version: number): boolean {
    return deps.orgId.value === orgId && loadVersion === version
  }

  /**
   * 组织级加载入口：一次 bump 域内版本（等价原 watcher 单版本四路共用的丢弃语义），
   * 商家资料与附件并行拉取。
   */
  function load(orgId: string): Promise<void> {
    const version = ++loadVersion
    return Promise.all([
      loadProfile(orgId, version),
      loadAttachments(orgId, version),
    ]).then(() => undefined)
  }

  async function loadProfile(orgId: string, version: number): Promise<void> {
    readError.value = ''
    profileLoaded.value = false
    try {
      const result = await grassland.getMerchantProfile(orgId)
      if (!isCurrentOrganization(orgId, version)) return
      profileLoaded.value = true
      fieldErrors.value = {}
      if (result) {
        profile.value = result
        // 回填表单
        const address = parseAddress(result.businessAddress)
        form.value = {
          legalName: result.legalName || '',
          unifiedSocialCreditCode: result.unifiedSocialCreditCode || '',
          industry: result.industry || '',
          businessType: result.businessType || '',
          legalPersonName: result.legalPersonName || '',
          legalPersonIdNumber: '',
          registeredCapitalYuan: result.registeredCapitalCents ? (result.registeredCapitalCents / 100).toFixed(2) : '',
          establishmentDate: result.establishmentDate || '',
          businessAddressProvince: address?.province || '',
          businessAddressCity: address?.city || '',
          businessAddressDistrict: address?.district || '',
          businessAddressDetail: address?.address || '',
          contactPhone: result.contactPhone || '',
          contactEmail: result.contactEmail || '',
        }
      }
    } catch (error: unknown) {
      if (!isCurrentOrganization(orgId, version)) return
      readError.value = error instanceof Error ? error.message : '商家资料加载失败'
    }
  }

  async function loadAttachments(orgId: string, version: number): Promise<void> {
    const list = await grassland.listMerchantAttachments(orgId)
    if (list && isCurrentOrganization(orgId, version)) attachments.value = list
  }

  async function save(): Promise<void> {
    if (!validateForm()) return
    const orgId = deps.orgId.value
    const version = loadVersion
    const address = {
      province: form.value.businessAddressProvince,
      city: form.value.businessAddressCity,
      district: form.value.businessAddressDistrict,
      address: form.value.businessAddressDetail,
    }
    const input = {
      legalName: form.value.legalName || undefined,
      unifiedSocialCreditCode: form.value.unifiedSocialCreditCode || undefined,
      industry: INDUSTRY_OPTIONS.some((option) => option.value === form.value.industry)
        ? form.value.industry as Industry
        : undefined,
      businessType: form.value.businessType || undefined,
      legalPersonName: form.value.legalPersonName || undefined,
      legalPersonIdNumber: form.value.legalPersonIdNumber.trim().toUpperCase() || undefined,
      registeredCapitalCents: form.value.registeredCapitalYuan
        ? Math.round(parseFloat(form.value.registeredCapitalYuan) * 100)
        : undefined,
      establishmentDate: form.value.establishmentDate || undefined,
      businessAddress: address.address ? address : undefined,
      contactPhone: form.value.contactPhone.trim() || undefined,
      contactEmail: form.value.contactEmail.trim() || undefined,
    }
    const result = profile.value
      ? await grassland.updateMerchantProfile(orgId, input)
      : await grassland.createMerchantProfile(orgId, input)
    if (result && isCurrentOrganization(orgId, version)) {
      profile.value = result
      deps.onChanged()
    }
  }

  async function submit(): Promise<void> {
    if (!validateForm() || !canSubmit.value) return
    const orgId = deps.orgId.value
    const version = loadVersion
    const result = await grassland.submitMerchantProfile(orgId)
    if (result && isCurrentOrganization(orgId, version)) {
      profile.value = result
      deps.onChanged()
    }
  }

  async function handleFileUpload(event: Event, attachmentType: MerchantAttachmentType): Promise<void> {
    const input = event.target as HTMLInputElement
    const file = input.files?.[0]
    if (!file) return
    const orgId = deps.orgId.value
    const version = loadVersion
    const result = await grassland.uploadMerchantAttachment(orgId, file, attachmentType)
    if (result && isCurrentOrganization(orgId, version)) {
      attachments.value = [...attachments.value, result]
      input.value = ''
    }
  }

  async function deleteAttachment(attachmentId: string): Promise<void> {
    const orgId = deps.orgId.value
    const version = loadVersion
    const result = await grassland.deleteMerchantAttachment(orgId, attachmentId)
    if (result !== null && isCurrentOrganization(orgId, version)) {
      attachments.value = attachments.value.filter((a) => a.id !== attachmentId)
    }
  }

  function reset(): void {
    profile.value = null
    attachments.value = []
    readError.value = ''
    profileLoaded.value = false
    fieldErrors.value = {}
    form.value = {
      legalName: '', unifiedSocialCreditCode: '', industry: '', businessType: '', legalPersonName: '',
      legalPersonIdNumber: '', registeredCapitalYuan: '', establishmentDate: '',
      businessAddressProvince: '', businessAddressCity: '', businessAddressDistrict: '',
      businessAddressDetail: '', contactPhone: '', contactEmail: '',
    }
  }

  return {
    profile, attachments, form, readError, profileLoaded, fieldErrors,
    businessTypeOptions, industryOptions,
    provinceOptions, cityOptions, districtOptions,
    canSubmit, canEdit, canEditPermissionSupplements, canEditAttachment,
    validateField, clearFieldError, onProvinceChange, onCityChange,
    save, submit, handleFileUpload, deleteAttachment,
    load, reset,
  }
}
