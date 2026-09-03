import { computed, ref, watch, type Ref } from 'vue'
import { useGrassland } from '../../composables/useGrassland'
import { validatePhone } from '../../lib/kyb-validation'
import { buildRegionCascade, parseAddress } from './kyb-shared'
import type { StoreProfile } from '../../types/grassland'

/**
 * 任务书 #68 卡 F：门店资料域（自 MerchantKybCard script 迁出，逻辑逐字符保真）。
 *
 * ⚠ grassland 实例由父组件创建后传入——useGrassland() 不是单例（每次调用各自新建
 * loading/error ref），各域自行调用会让「任一操作禁用全部按钮」的现状行为漂移。
 */
type GrasslandApi = ReturnType<typeof useGrassland>

interface StoreDomainDeps {
  orgId: Ref<string>
  grassland: GrasslandApi
  /** 外部注入门店列表（纯门店经理无组织成员身份，listStores 会被 403，改由工作台注入）。 */
  storesProp: Ref<Array<{ id: string; name: string }> | undefined>
  onChanged: () => void
}

export type StoreValidationField = 'phone'

export function useStoreDomain(deps: StoreDomainDeps) {
  const grassland = deps.grassland

  const options = ref<{ id: string; name: string }[]>([])
  const selectedId = ref('')
  const profile = ref<StoreProfile | null>(null)
  const form = ref({
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

  const readError = ref('')
  const profileLoaded = ref(false)
  const fieldErrors = ref<Partial<Record<StoreValidationField, string>>>({})

  let organizationLoadVersion = 0
  let storeOperationVersion = 0

  const { provinceOptions, cityOptions, districtOptions } = buildRegionCascade(
    () => form.value.addressProvince,
    () => form.value.addressCity,
    () => form.value.addressDistrict,
  )

  function phoneError(): string | null {
    return validatePhone(form.value.phone)
  }

  const canEdit = computed(() => profileLoaded.value && !readError.value
    && (!profile.value
      || ['draft', 'rejected', 'inactive'].includes(profile.value.status)))

  const canSubmit = computed(() => profile.value !== null
    && ['draft', 'rejected'].includes(profile.value.status)
    && !phoneError())

  function setFieldError(field: StoreValidationField, message: string | null): void {
    const next = { ...fieldErrors.value }
    if (message) next[field] = message
    else delete next[field]
    fieldErrors.value = next
  }

  function validateField(field: StoreValidationField): boolean {
    const error = field === 'phone' ? phoneError() : null
    setFieldError(field, error)
    return !error
  }

  function validateForm(): boolean {
    return validateField('phone')
  }

  function clearFieldError(field: StoreValidationField): void {
    if (fieldErrors.value[field]) setFieldError(field, null)
  }

  function onProvinceChange(): void {
    form.value.addressCity = ''
    form.value.addressDistrict = ''
  }

  function onCityChange(): void {
    form.value.addressDistrict = ''
  }

  function emptyStoreForm(): typeof form.value {
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

  function isCurrentOrganization(orgId: string, version: number): boolean {
    return deps.orgId.value === orgId && organizationLoadVersion === version
  }

  function isCurrentStoreOperation(
    orgId: string,
    organizationVersion: number,
    storeId: string,
    operationVersion: number,
  ): boolean {
    return isCurrentOrganization(orgId, organizationVersion)
      && selectedId.value === storeId
      && storeOperationVersion === operationVersion
  }

  async function loadStores(orgId: string): Promise<void> {
    const version = ++organizationLoadVersion
    if (Array.isArray(deps.storesProp.value)) {
      if (isCurrentOrganization(orgId, version)) {
        options.value = [...deps.storesProp.value]
        if (options.value.length > 0 && !selectedId.value) {
          selectedId.value = options.value[0].id
        }
      }
      return
    }
    const list = await grassland.listStores(orgId)
    if (list && isCurrentOrganization(orgId, version)) {
      options.value = list
      if (list.length > 0 && !selectedId.value) {
        selectedId.value = list[0].id
      }
    }
  }

  async function loadStoreProfile(): Promise<void> {
    if (!selectedId.value) return
    const orgId = deps.orgId.value
    const version = organizationLoadVersion
    const storeId = selectedId.value
    const operationVersion = ++storeOperationVersion
    readError.value = ''
    profileLoaded.value = false
    profile.value = null
    form.value = emptyStoreForm()
    try {
      const result = await grassland.getStoreProfile(orgId, storeId)
      if (!isCurrentStoreOperation(orgId, version, storeId, operationVersion)) return
      profileLoaded.value = true
      fieldErrors.value = {}
      if (result) {
        profile.value = result
        const address = parseAddress(result.address)
        form.value = {
          addressProvince: address?.province || '',
          addressCity: address?.city || '',
          addressDistrict: address?.district || '',
          addressDetail: address?.address || '',
          phone: result.phone || '',
          description: result.description || '',
          categories: (result.categories ?? []).join('\n'),
          signatureItems: (result.signatureItems ?? []).join('\n'),
          sellingPoints: (result.sellingPoints ?? []).join('\n'),
          mustEmphasize: (result.mustEmphasize ?? []).join('\n'),
          forbiddenPhrases: (result.forbiddenPhrases ?? []).join('\n'),
          allowedTags: (result.allowedTags ?? []).join('\n'),
          brandTone: result.brandTone || '',
          priceRange: result.priceRange || '',
          averageSpendYuan: result.averageSpendCents == null
            ? ''
            : String(result.averageSpendCents / 100),
          visitNotes: result.visitNotes || '',
        }
      }
    } catch (error: unknown) {
      if (!isCurrentStoreOperation(orgId, version, storeId, operationVersion)) return
      readError.value = error instanceof Error ? error.message : '门店资料加载失败'
    }
  }

  async function save(): Promise<void> {
    if (!selectedId.value || !validateForm()) return
    const orgId = deps.orgId.value
    const version = organizationLoadVersion
    const storeId = selectedId.value
    const operationVersion = ++storeOperationVersion
    const address = {
      province: form.value.addressProvince,
      city: form.value.addressCity,
      district: form.value.addressDistrict,
      address: form.value.addressDetail,
    }
    const result = await grassland.createStoreProfile(orgId, storeId, {
      address: Object.values(address).some(Boolean) ? JSON.stringify(address) : undefined,
      phone: form.value.phone.trim() || undefined,
      description: form.value.description || undefined,
      // 任务书 #24：营销字段整份覆盖（后端空数组 = 清空），列表按换行拆行。
      categories: storeFormLines(form.value.categories),
      signatureItems: storeFormLines(form.value.signatureItems),
      sellingPoints: storeFormLines(form.value.sellingPoints),
      mustEmphasize: storeFormLines(form.value.mustEmphasize),
      forbiddenPhrases: storeFormLines(form.value.forbiddenPhrases),
      allowedTags: storeFormLines(form.value.allowedTags),
      brandTone: form.value.brandTone || undefined,
      priceRange: form.value.priceRange || undefined,
      averageSpendCents: averageSpendToCents(form.value.averageSpendYuan),
      visitNotes: form.value.visitNotes || undefined,
    })
    if (result && isCurrentStoreOperation(orgId, version, storeId, operationVersion)) {
      profile.value = result
      deps.onChanged()
    }
  }

  async function submit(): Promise<void> {
    if (!selectedId.value || !validateForm() || !canSubmit.value) return
    const orgId = deps.orgId.value
    const version = organizationLoadVersion
    const storeId = selectedId.value
    const operationVersion = ++storeOperationVersion
    const result = await grassland.submitStoreProfile(orgId, storeId)
    if (result && isCurrentStoreOperation(orgId, version, storeId, operationVersion)) {
      profile.value = result
      deps.onChanged()
    }
  }

  watch(selectedId, () => {
    profile.value = null
    profileLoaded.value = false
    fieldErrors.value = {}
    form.value = emptyStoreForm()
    void loadStoreProfile()
  })

  // 门店列表 prop 变化时同步下拉（新建门店后不刷新页面即可见）
  watch(deps.storesProp, (next) => {
    if (Array.isArray(next)) {
      options.value = [...next]
      if (options.value.length > 0 && !selectedId.value) {
        selectedId.value = options.value[0].id
      }
    }
  }, { deep: true })

  function reset(): void {
    storeOperationVersion += 1
    options.value = []
    selectedId.value = ''
    profile.value = null
    readError.value = ''
    profileLoaded.value = false
    fieldErrors.value = {}
    form.value = emptyStoreForm()
  }

  return {
    options, selectedId, profile, form, readError, profileLoaded, fieldErrors,
    provinceOptions, cityOptions, districtOptions,
    canEdit, canSubmit,
    validateField, clearFieldError, onProvinceChange, onCityChange,
    save, submit,
    loadStores, reset,
  }
}
