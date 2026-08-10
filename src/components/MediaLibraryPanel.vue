<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useGrassland } from '../composables/useGrassland'
import { useAuth } from '../composables/useAuth'
import MediaUploader from './MediaUploader.vue'
import type {
  ContentAsset,
  ContentAssetCategory,
  ContentLibraryType,
  IdentityProfile,
  Store,
  StoreAccessScope,
} from '../types/grassland'

/**
 * 内容素材库面板（PRD §4.8 / Slice 14 Stage 4）。
 *
 * 三个子 tab 对应三类素材库，按身份显隐与分流：
 * - 个人（personal）：任意登录用户，自己的 active 素材，可上传/编辑/删除。
 * - 商家（merchant）：商家身份（有 merchant IdentityProfile + org），本 org 素材 + 授权推荐官。
 *   商家可上传/编辑/删除 + 授权推荐官；推荐官（无商家身份）只看「被授权」的商家素材（只读）。
 * - 公共（public）：全员只读（含未登录），active 未过期；内容审核员可在此看自己上传的 pending（审核端在管理台）。
 *
 * 嵌入 AiCreationCenter 第 4 tab；下载走后端中转签短时 URL，不预渲染。
 */

const props = defineProps<{ authenticated: boolean }>()
const emit = defineEmits<{ 'request-login': [] }>()

const grassland = useGrassland()
const auth = useAuth()

type LibraryTab = 'personal' | 'merchant' | 'public'
const activeTab = ref<LibraryTab>('personal')
const identities = ref<IdentityProfile[]>([])
const assets = ref<ContentAsset[]>([])
const notice = ref('')
const showUpload = ref(false)
/** 商家子模式：自己管理（默认）还是看被授权的推荐官视角。 */
const grantedView = ref(false)
const stores = ref<Store[]>([])
const storeScopes = ref<StoreAccessScope[]>([])
const selectedOrganizationId = ref('')
/** Empty means organization-level merchant assets; otherwise the selected store scope. */
const selectedStoreId = ref('')

const CATEGORIES: ReadonlyArray<{ id: ContentAssetCategory; label: string }> = [
  { id: 'store', label: '门店' },
  { id: 'product', label: '产品' },
  { id: 'campaign', label: '活动' },
  { id: 'scene', label: '场景' },
  { id: 'brand', label: '品牌' },
  { id: 'copy', label: '文案' },
  { id: 'other', label: '其他' },
]

/** 当前活动商家身份（用于商家库 org 归属）；null = 无商家身份（推荐官/消费者）。 */
const merchantIdentity = computed(() =>
  identities.value.find((i) => i.identityType === 'merchant' && i.organizationId) || null)
/** 是否为推荐官（有 recommender 身份）。 */
const isRecommender = computed(() =>
  identities.value.some((i) => i.identityType === 'recommender'))
const managerStoreScopes = computed(() => storeScopes.value.filter((scope) => scope.role === 'manager'))
const merchantOrganizations = computed(() => {
  const options: Array<{ id: string; name: string }> = []
  if (merchantIdentity.value?.organizationId) {
    options.push({ id: merchantIdentity.value.organizationId, name: '我的商家组织' })
  }
  for (const scope of managerStoreScopes.value) {
    if (!options.some((option) => option.id === scope.organizationId)) {
      options.push({ id: scope.organizationId, name: scope.organizationName })
    }
  }
  return options
})
const hasOrganizationManagement = computed(() =>
  merchantIdentity.value?.organizationId === selectedOrganizationId.value)
const canManageCurrentMerchantScope = computed(() => {
  if (!selectedOrganizationId.value || grantedView.value) return false
  if (!selectedStoreId.value) return hasOrganizationManagement.value
  return hasOrganizationManagement.value || managerStoreScopes.value.some((scope) =>
    scope.organizationId === selectedOrganizationId.value
      && scope.storeId === selectedStoreId.value)
})
const canReviewPublic = computed(() => auth.hasBackendRole('content_reviewer'))

/** 商家 tab 可见：有商家身份（管理自己的）或有推荐官身份（看被授权的）。 */
const merchantTabVisible = computed(() =>
  merchantIdentity.value !== null || managerStoreScopes.value.length > 0 || isRecommender.value)

function selectTab(tab: LibraryTab): void {
  if (tab !== 'public' && !props.authenticated) {
    emit('request-login')
    return
  }
  activeTab.value = tab
  if (tab === 'merchant') {
    // 商家成员默认管理视角；推荐官默认看被授权的。
    grantedView.value = merchantIdentity.value === null
      && managerStoreScopes.value.length === 0 && isRecommender.value
  }
  void refresh()
}

onMounted(async () => {
  if (props.authenticated) {
    const [list, scopes] = await Promise.all([
      grassland.listIdentities(), grassland.listMyStoreScopes(),
    ])
    if (list) identities.value = list
    if (Array.isArray(scopes)) storeScopes.value = scopes
    selectedOrganizationId.value = merchantIdentity.value?.organizationId
      ?? managerStoreScopes.value[0]?.organizationId ?? ''
    await loadMerchantStores()
  }
  await refresh()
})

async function loadMerchantStores(): Promise<void> {
  const organizationId = selectedOrganizationId.value
  if (!organizationId) {
    stores.value = []
    selectedStoreId.value = ''
    return
  }
  const listed = hasOrganizationManagement.value
    ? (await grassland.listStores(organizationId)) ?? []
    : []
  const merged = [...listed]
  for (const scope of managerStoreScopes.value.filter((item) => item.organizationId === organizationId)) {
    if (!merged.some((store) => store.id === scope.storeId)) {
      merged.push({
        id: scope.storeId,
        organizationId: scope.organizationId,
        name: scope.storeName,
        status: scope.storeStatus,
        createdAt: null,
      })
    }
  }
  stores.value = merged
  if (!hasOrganizationManagement.value
      && !stores.value.some((store) => store.id === selectedStoreId.value)) {
    selectedStoreId.value = stores.value[0]?.id ?? ''
  }
}

async function changeMerchantOrganization(): Promise<void> {
  selectedStoreId.value = ''
  await loadMerchantStores()
  await refresh()
}

async function refresh(): Promise<void> {
  notice.value = ''
  assets.value = []
  if (activeTab.value === 'public') {
    const result = await grassland.listContentAssets({ libraryType: 'public' })
    if (result) assets.value = result.items
    return
  }
  if (activeTab.value === 'merchant') {
    if (grantedView.value && isRecommender.value) {
      const result = await grassland.listContentAssets({ libraryType: 'merchant', granted: true })
      if (result) assets.value = result.items
      return
    }
    if (canManageCurrentMerchantScope.value) {
      const result = await grassland.listContentAssets({
        libraryType: 'merchant',
        organizationId: selectedOrganizationId.value || undefined,
        storeId: selectedStoreId.value || undefined,
      })
      if (result) assets.value = result.items
    }
    return
  }
  // personal
  const result = await grassland.listContentAssets({ libraryType: 'personal' })
  if (result) assets.value = result.items
}

async function handleUploaded(mediaIds: string[]): Promise<void> {
  if (mediaIds.length === 0) return
  // 上传后立即挂接最后一张（简易：一次挂一张，用户可后续编辑）。
  const mediaId = mediaIds[mediaIds.length - 1]
  const libraryType: ContentLibraryType = activeTab.value === 'public' ? 'public' : activeTab.value
  const input = activeTab.value === 'public'
    ? {
        libraryType: 'public' as const,
        mediaId,
        category: 'scene' as ContentAssetCategory,
        title: '公共素材',
        source: '平台素材',
        licenseScope: '公开授权',
        validUntil: '2027-12-31T23:59:59Z',
      }
      : {
        libraryType,
        mediaId,
        category: 'other' as ContentAssetCategory,
        title: '未命名素材',
        organizationId: selectedOrganizationId.value || undefined,
        storeId: selectedStoreId.value || undefined,
      }
  const created = await grassland.createContentAsset(input)
  if (created) {
    notice.value = '素材已添加'
    showUpload.value = false
    await refresh()
  }
}

async function remove(asset: ContentAsset): Promise<void> {
  if (!confirm(`删除「${asset.title}」？`)) return
  const result = await grassland.deleteContentAsset(asset.id)
  if (result) {
    notice.value = '已删除'
    await refresh()
  }
}

async function download(asset: ContentAsset): Promise<void> {
  const dl = await grassland.getContentAssetDownloadUrl(asset.id)
  if (dl) window.open(dl.downloadUrl, '_blank', 'noopener,noreferrer')
}

async function grant(asset: ContentAsset): Promise<void> {
  const grantee = prompt('输入要授权的推荐官账号 ID')
  if (!grantee) return
  const result = await grassland.grantContentAsset(asset.id, grantee.trim())
  if (result) notice.value = `已授权给 ${grantee.trim()}`
}

function categoryLabel(category: ContentAssetCategory): string {
  return CATEGORIES.find((c) => c.id === category)?.label || category
}

function formatSize(bytes: number | null | undefined): string {
  if (bytes == null) return ''
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(0)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}
</script>

<template>
  <section class="library">
    <p v-if="grassland.error.value" class="lib-alert lib-err" role="alert">{{ grassland.error.value }}</p>
    <p v-if="notice" class="lib-alert lib-ok">{{ notice }}</p>

    <nav class="lib-tabs" role="tablist">
      <button type="button" role="tab" :aria-selected="activeTab === 'personal'"
        :class="{ active: activeTab === 'personal' }" @click="selectTab('personal')">个人素材</button>
      <button v-if="merchantTabVisible" type="button" role="tab" :aria-selected="activeTab === 'merchant'"
        :class="{ active: activeTab === 'merchant' }" @click="selectTab('merchant')">
        {{ grantedView ? '商家素材（授权给我）' : '商家素材' }}
      </button>
      <button v-if="grantedView && isRecommender" type="button" role="tab"
        :aria-selected="!grantedView" @click="grantedView = false; refresh()">切换为管理视角</button>
      <button type="button" role="tab" :aria-selected="activeTab === 'public'"
        :class="{ active: activeTab === 'public' }" @click="selectTab('public')">公共素材</button>
    </nav>

    <!-- 上传区（个人/商家/审核员，公共需 content_reviewer） -->
    <div v-if="activeTab === 'personal' || activeTab === 'public' && canReviewPublic
      || activeTab === 'merchant' && canManageCurrentMerchantScope" class="lib-actions">
      <button v-if="!showUpload" type="button" @click="showUpload = true">添加素材</button>
      <MediaUploader v-else :key="activeTab" @change="handleUploaded" />
    </div>
    <div v-if="activeTab === 'merchant' && !grantedView && merchantOrganizations.length > 0" class="lib-scope">
      <label v-if="merchantOrganizations.length > 1">组织
        <select v-model="selectedOrganizationId" @change="changeMerchantOrganization">
          <option v-for="organization in merchantOrganizations" :key="organization.id" :value="organization.id">
            {{ organization.name }}
          </option>
        </select>
      </label>
      <label>资源范围
        <select v-model="selectedStoreId" @change="refresh">
          <option v-if="hasOrganizationManagement" value="">组织级素材</option>
          <option v-for="store in stores" :key="store.id" :value="store.id">门店：{{ store.name }}</option>
        </select>
      </label>
    </div>

    <ul v-if="assets.length > 0" class="lib-grid">
      <li v-for="asset in assets" :key="asset.id" class="lib-card">
        <div class="lib-card-head">
          <span class="lib-title">{{ asset.title }}</span>
          <span class="lib-cat">{{ categoryLabel(asset.category) }}</span>
        </div>
        <p v-if="asset.mimeType || asset.sizeBytes" class="lib-meta">
          {{ asset.mimeType }} · {{ formatSize(asset.sizeBytes) }}
        </p>
        <p v-if="asset.tags.length > 0" class="lib-tags">{{ asset.tags.join('、') }}</p>
        <p v-if="asset.source" class="lib-source">来源：{{ asset.source }}</p>
        <p v-if="asset.storeId" class="lib-source">
          范围：{{ stores.find((store) => store.id === asset.storeId)?.name || '门店素材' }}
        </p>
        <div class="lib-card-actions">
          <button type="button" :disabled="grassland.loading.value" @click="download(asset)">下载</button>
          <template v-if="activeTab === 'merchant' && canManageCurrentMerchantScope">
            <button type="button" :disabled="grassland.loading.value" @click="grant(asset)">授权推荐官</button>
            <button type="button" :disabled="grassland.loading.value" @click="remove(asset)">删除</button>
          </template>
          <button v-else-if="activeTab === 'personal'" type="button" :disabled="grassland.loading.value"
            @click="remove(asset)">删除</button>
        </div>
      </li>
    </ul>

    <p v-else class="lib-empty">
      {{ activeTab === 'public' ? '暂无公共素材。' : '还没有素材，点「添加素材」上传第一份。' }}
    </p>
  </section>
</template>

<style scoped>
.library { display: flex; flex-direction: column; gap: 12px; }
.lib-alert { margin: 0; padding: 6px 10px; border-radius: 6px; font-size: 12px; }
.lib-err { background: color-mix(in srgb, var(--color-danger) 14%, transparent); color: var(--color-danger); }
.lib-ok { background: color-mix(in srgb, var(--color-success) 14%, transparent); color: var(--color-success); }
.lib-tabs { display: flex; gap: 4px; flex-wrap: wrap; }
.lib-tabs button { padding: 6px 14px; border: 1px solid var(--color-border); background: transparent; color: var(--color-text); border-radius: 6px; cursor: pointer; font-size: 13px; }
.lib-tabs button.active { border-color: var(--color-accent); background: color-mix(in srgb, var(--color-accent) 12%, transparent); }
.lib-actions { display: flex; gap: 8px; }
.lib-scope { display: flex; align-items: center; gap: 8px; font-size: 12px; }
.lib-scope select { padding: 4px 8px; border: 1px solid var(--color-border); border-radius: 5px; background: var(--color-surface); color: var(--color-text); }
.lib-actions button { padding: 6px 14px; border: 1px solid var(--color-border); background: transparent; color: var(--color-text); border-radius: 6px; cursor: pointer; font-size: 13px; }
.lib-grid { list-style: none; margin: 0; padding: 0; display: grid; grid-template-columns: repeat(auto-fill, minmax(220px, 1fr)); gap: 10px; }
.lib-card { display: flex; flex-direction: column; gap: 4px; padding: 10px; border: 1px solid var(--color-border); border-radius: 8px; }
.lib-card-head { display: flex; align-items: center; gap: 6px; justify-content: space-between; }
.lib-title { font-size: 13px; font-weight: 500; word-break: break-all; }
.lib-cat { font-size: 11px; padding: 1px 6px; border-radius: 4px; background: var(--color-surface-strong); white-space: nowrap; }
.lib-meta, .lib-tags, .lib-source { margin: 0; font-size: 11px; opacity: 0.65; word-break: break-all; }
.lib-card-actions { display: flex; gap: 4px; flex-wrap: wrap; margin-top: 4px; }
.lib-card-actions button { padding: 3px 10px; font-size: 12px; border: 1px solid var(--color-border); background: transparent; color: var(--color-text); border-radius: 6px; cursor: pointer; }
.lib-card-actions button:hover:not(:disabled) { border-color: var(--color-border-hover); background: var(--color-surface-hover); }
.lib-card-actions button:disabled { opacity: 0.5; cursor: not-allowed; }
.lib-empty { margin: 0; font-size: 13px; opacity: 0.6; }
</style>
