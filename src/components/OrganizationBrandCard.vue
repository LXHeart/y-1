<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { GrasslandHttpError } from '../composables/grassland-http'
import { useGrasslandIdentity } from '../composables/useGrasslandIdentity'
import type { BrandProfile, Industry, MembershipRole } from '../types/grassland'

/**
 * 组织品牌资料卡片（#32，D11）：独立于 MerchantKybCard 的门店资料（后者走 KYB 审核状态机，
 * 本卡随时可编辑）。owner/admin 编辑、member 只读；保存带 expectedVersion 乐观锁，
 * 409 时展示后端冲突文案并自动重拉最新资料。
 */
interface Props {
  orgId: string
  /** 当前账号在该组织的角色（/api/me/organization-scopes 权威口径）；member 或未知 → 只读。 */
  role?: MembershipRole | null
}

const props = withDefaults(defineProps<Props>(), { role: null })

/**
 * 透传 run（照 AiOrgBudgetPanel 先例）：不吞错，保留 `GrasslandHttpError.status` 供
 * 409 乐观锁精确分支（默认 `useGrassland().run` 会吞成 null + 全局 error，状态拿不到）。
 */
const brandApi = useGrasslandIdentity(async (operation) => operation())

const canEdit = computed(() => props.role === 'owner' || props.role === 'admin')

/** 13 值经营分类（镜像 identity `Industry` 枚举 dbValue；与 organization.industry 互不影响，D10）。 */
const INDUSTRY_OPTIONS: Array<{ value: Industry; label: string }> = [
  { value: 'catering', label: '餐饮' },
  { value: 'retail', label: '零售' },
  { value: 'beauty', label: '美业' },
  { value: 'education', label: '教育' },
  { value: 'e_commerce', label: '电商' },
  { value: 'healthcare', label: '医疗健康' },
  { value: 'finance', label: '金融服务' },
  { value: 'real_estate', label: '房地产' },
  { value: 'travel', label: '旅游' },
  { value: 'children', label: '母婴儿童' },
  { value: 'gambling', label: '博彩' },
  { value: 'adult', label: '成人内容' },
  { value: 'other', label: '其他' },
]

const industryLabel = (value: Industry | null | undefined): string =>
  INDUSTRY_OPTIONS.find((option) => option.value === value)?.label ?? ''

const profile = ref<BrandProfile | null>(null)
const form = ref({
  brandName: '',
  description: '',
  industry: '' as Industry | '',
})
/** 随下次保存提交的 Logo 引用；null = 清空（D8）。上传成功即换新 id，保存前就已生效于表单。 */
const logoMediaId = ref<string | null>(null)
/** 展示用预览：本地 object URL（新上传）或服务端短时效 logoUrl（回填）。 */
const logoPreviewUrl = ref<string | null>(null)
let logoObjectUrl: string | null = null

const loading = ref(false)
const uploading = ref(false)
const saving = ref(false)
const error = ref('')
const notice = ref('')

/** 防串扰版本号（照 MerchantKybCard 的 organizationLoadVersion 模式）。 */
let organizationLoadVersion = 0

function isCurrentOrganization(orgId: string, version: number): boolean {
  return props.orgId === orgId && organizationLoadVersion === version
}

function setLocalPreview(file: File): void {
  if (logoObjectUrl) URL.revokeObjectURL(logoObjectUrl)
  logoObjectUrl = URL.createObjectURL(file)
  logoPreviewUrl.value = logoObjectUrl
}

function clearPreview(): void {
  if (logoObjectUrl) URL.revokeObjectURL(logoObjectUrl)
  logoObjectUrl = null
}

function applyProfile(value: BrandProfile): void {
  profile.value = value
  form.value = {
    brandName: value.brandName || '',
    description: value.description || '',
    industry: value.industry || '',
  }
  logoMediaId.value = value.brandLogoMediaReferenceId ?? null
  clearPreview()
  logoPreviewUrl.value = value.logoUrl || null
}

async function loadBrandProfile(orgId: string, version: number): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const loaded = await brandApi.getBrandProfile(orgId)
    if (!isCurrentOrganization(orgId, version)) return
    if (loaded) applyProfile(loaded)
  } catch (caught: unknown) {
    if (!isCurrentOrganization(orgId, version)) return
    error.value = caught instanceof Error ? caught.message : '品牌资料加载失败'
  } finally {
    if (isCurrentOrganization(orgId, version)) loading.value = false
  }
}

/** 409 冲突后的静默重拉：刷新 version 与表单为服务器最新态，不打断已展示的冲突提示。 */
async function reloadLatest(orgId: string, version: number): Promise<void> {
  try {
    const latest = await brandApi.getBrandProfile(orgId)
    if (isCurrentOrganization(orgId, version) && latest) applyProfile(latest)
  } catch {
    // 重拉失败保留冲突提示即可，用户可手工再试。
  }
}

async function save(): Promise<void> {
  if (!canEdit.value || saving.value) return
  const orgId = props.orgId
  const version = organizationLoadVersion
  notice.value = ''
  error.value = ''
  saving.value = true
  try {
    const saved = await brandApi.updateBrandProfile(orgId, {
      // PUT 整份覆盖：空值显式发 null（清空语义，D3/D8）。
      brandName: form.value.brandName.trim() || null,
      brandLogoMediaReferenceId: logoMediaId.value,
      description: form.value.description.trim() || null,
      industry: form.value.industry || null,
      expectedVersion: profile.value?.version ?? 0,
    })
    if (!isCurrentOrganization(orgId, version)) return
    if (!saved) return   // 透传 run 理论不返回 null（失败走 catch），类型上仍需收窄
    applyProfile(saved)
    notice.value = '品牌资料已保存'
  } catch (caught: unknown) {
    if (!isCurrentOrganization(orgId, version)) return
    if (caught instanceof GrasslandHttpError && caught.status === 409) {
      // 乐观锁冲突：后端文案（「品牌资料已变更，请刷新后重试」）+ 自动重拉最新资料。
      error.value = caught.message
      await reloadLatest(orgId, version)
    } else {
      error.value = caught instanceof Error ? caught.message : '品牌资料保存失败'
    }
  } finally {
    if (isCurrentOrganization(orgId, version)) saving.value = false
  }
}

/** Logo 选择：三步上传（压缩在 API 层完成）→ 记 mediaId 随整份保存生效 + 本地预览。 */
async function onLogoChange(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  const orgId = props.orgId
  const version = organizationLoadVersion
  notice.value = ''
  error.value = ''
  uploading.value = true
  try {
    const mediaId = await brandApi.uploadBrandLogo(orgId, file)
    if (!isCurrentOrganization(orgId, version)) return
    if (mediaId === null) return   // 同上：透传 run 的类型收窄
    logoMediaId.value = mediaId
    setLocalPreview(file)
    notice.value = '品牌 Logo 已上传，点「保存资料」后生效'
  } catch (caught: unknown) {
    if (!isCurrentOrganization(orgId, version)) return
    error.value = caught instanceof Error ? caught.message : '品牌 Logo 上传失败'
  } finally {
    if (isCurrentOrganization(orgId, version)) uploading.value = false
  }
}

function removeLogo(): void {
  logoMediaId.value = null
  clearPreview()
  logoPreviewUrl.value = null
}

function resetState(): void {
  profile.value = null
  form.value = { brandName: '', description: '', industry: '' }
  logoMediaId.value = null
  clearPreview()
  logoPreviewUrl.value = null
  loading.value = false
  uploading.value = false
  saving.value = false
  error.value = ''
  notice.value = ''
}

watch(() => props.orgId, (orgId) => {
  const version = ++organizationLoadVersion
  resetState()
  void loadBrandProfile(orgId, version)
}, { immediate: true })

onBeforeUnmount(() => {
  clearPreview()
})
</script>

<template>
  <div class="brand-card">
    <h3>品牌资料</h3>
    <p class="brand-sub">品牌名称、Logo、商家简介与经营分类；与门店资料相互独立，随时可编辑。</p>

    <p v-if="error" class="brand-alert brand-error" role="alert">{{ error }}</p>
    <p v-if="notice" class="brand-alert brand-ok">{{ notice }}</p>

    <form v-if="canEdit" class="brand-form" @submit.prevent>
      <div class="brand-row">
        <label>品牌名称
          <input v-model="form.brandName" placeholder="请输入品牌名称" :disabled="loading" />
        </label>
        <label>经营分类
          <select v-model="form.industry" :disabled="loading">
            <option value="">未设置</option>
            <option v-for="option in INDUSTRY_OPTIONS" :key="option.value" :value="option.value">
              {{ option.label }}
            </option>
          </select>
        </label>
      </div>
      <div class="brand-row">
        <label>商家简介
          <textarea v-model="form.description" rows="4" placeholder="请输入商家简介（选填）" :disabled="loading" />
        </label>
      </div>

      <div class="brand-logo">
        <img v-if="logoPreviewUrl" :src="logoPreviewUrl" alt="品牌 Logo 预览" class="brand-logo-img" />
        <div v-else class="brand-logo-empty">暂无 Logo</div>
        <label class="brand-logo-pick">
          <input
            type="file"
            accept="image/png,image/jpeg,image/webp"
            :disabled="uploading || saving"
            @change="onLogoChange"
          />
          <span>{{ uploading ? '上传中…' : '选择 Logo' }}</span>
        </label>
        <button
          v-if="logoMediaId || logoPreviewUrl"
          type="button"
          class="brand-logo-remove"
          :disabled="uploading || saving"
          @click="removeLogo"
        >移除 Logo</button>
      </div>

      <div class="brand-actions">
        <button
          type="button"
          :disabled="loading || uploading || saving"
          @click="save"
        >保存资料</button>
      </div>
    </form>

    <div v-else class="brand-readonly" aria-label="品牌资料（只读）">
      <dl>
        <div class="brand-readonly-row">
          <dt>品牌名称</dt>
          <dd>{{ profile?.brandName || '未设置' }}</dd>
        </div>
        <div class="brand-readonly-row">
          <dt>经营分类</dt>
          <dd>{{ industryLabel(profile?.industry) || '未设置' }}</dd>
        </div>
        <div class="brand-readonly-row">
          <dt>商家简介</dt>
          <dd class="brand-readonly-desc">{{ profile?.description || '未设置' }}</dd>
        </div>
        <div class="brand-readonly-row">
          <dt>品牌 Logo</dt>
          <dd>
            <img v-if="logoPreviewUrl" :src="logoPreviewUrl" alt="品牌 Logo" class="brand-logo-img" />
            <span v-else>未设置</span>
          </dd>
        </div>
      </dl>
      <p class="brand-readonly-hint">当前角色只读，品牌资料仅组织 owner / admin 可编辑</p>
    </div>
  </div>
</template>

<style scoped>
.brand-card {
  width: 100%;
}

.brand-card h3 {
  margin: 0 0 4px 0;
  font-size: 16px;
  font-weight: 600;
}

.brand-sub {
  margin: 0 0 16px;
  color: #6b7280;
  font-size: 13px;
}

.brand-alert {
  margin: 0 0 12px;
  padding: 8px 12px;
  border-radius: 6px;
  font-size: 13px;
}

.brand-error {
  color: #b91c1c;
  background: #fef2f2;
}

.brand-ok {
  color: #047857;
  background: #ecfdf5;
}

.brand-form {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.brand-row {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 12px;
}

.brand-row label {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 13px;
  color: #374151;
}

.brand-row input,
.brand-row select,
.brand-row textarea {
  padding: 8px 12px;
  border: 1px solid #d1d5db;
  border-radius: 6px;
  font-size: 14px;
}

.brand-row textarea {
  resize: vertical;
}

.brand-row input:focus,
.brand-row select:focus,
.brand-row textarea:focus {
  outline: none;
  border-color: #3b82f6;
  box-shadow: 0 0 0 2px rgba(59, 130, 246, 0.1);
}

.brand-logo {
  display: flex;
  align-items: center;
  gap: 12px;
}

.brand-logo-img {
  width: 64px;
  height: 64px;
  border-radius: 8px;
  object-fit: cover;
  border: 1px solid #e5e7eb;
}

.brand-logo-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 64px;
  height: 64px;
  border: 1px dashed #d1d5db;
  border-radius: 8px;
  color: #9ca3af;
  font-size: 12px;
}

.brand-logo-pick {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 12px;
  border: 1px solid #d1d5db;
  border-radius: 6px;
  font-size: 13px;
  cursor: pointer;
  background: white;
}

.brand-logo-pick input[type="file"] {
  display: none;
}

.brand-logo-remove {
  padding: 6px 12px;
  border: none;
  background: none;
  color: #b91c1c;
  font-size: 13px;
  cursor: pointer;
}

.brand-actions {
  display: flex;
  gap: 8px;
  padding-top: 8px;
}

.brand-actions button {
  padding: 8px 16px;
  border: 1px solid #d1d5db;
  background: white;
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.2s;
}

.brand-actions button:hover:not(:disabled) {
  background: #f9fafb;
  border-color: #9ca3af;
}

.brand-actions button:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.brand-readonly dl {
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.brand-readonly-row {
  display: grid;
  grid-template-columns: 96px 1fr;
  gap: 12px;
  font-size: 14px;
}

.brand-readonly-row dt {
  color: #6b7280;
  font-size: 13px;
}

.brand-readonly-row dd {
  margin: 0;
  color: #111827;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.brand-readonly-hint {
  margin: 16px 0 0;
  color: #9ca3af;
  font-size: 12px;
}
</style>
