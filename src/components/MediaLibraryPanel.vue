<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useGrassland } from '../composables/useGrassland'
import { useAuth } from '../composables/useAuth'
import MediaUploader from './MediaUploader.vue'
import type {
  ContentAsset,
  ContentAssetCategory,
  ContentLibraryType,
  IdentityProfile,
  OrganizationAccessScope,
  Store,
  StoreAccessScope,
  SemanticRecommendationMetadata,
} from '../types/grassland'
import type { CreationRecommendationContext } from '../types/ai-creation'

/**
 * 内容素材库面板（PRD §4.8 / Slice 14 Stage 4）。
 *
 * 四个子 tab 对应素材库视图，按身份显隐与分流：
 * - 智能推荐（recommend）：按任务（applicationId+taskId，服务端拉权威任务上下文）或平台/内容形式
 *   推荐「本人可访问」素材（个人/被授权商家/本组织/公共），带分数与理由；仅登录用户。
 * - 个人（personal）：任意登录用户，自己的 active 素材，可上传/编辑/删除。
 * - 商家（merchant）：商家身份（有 merchant IdentityProfile + org），本 org 素材 + 授权推荐官。
 *   org admin/member 粒度：组织级素材管理（上传/删除/授权）仅 org owner/admin；member 只读。
 *   门店素材管理需门店 MANAGER 范围；推荐官（无商家身份）只看「被授权」的商家素材（只读）。
 * - 公共（public）：全员只读（含未登录），active 未过期；内容审核员可在此看自己上传的 pending（审核端在管理台）。
 *
 * 嵌入 AiCreationCenter 第 4 tab；下载走后端中转签短时 URL，不预渲染。
 */

const props = withDefaults(defineProps<{
  authenticated: boolean
  selectable?: boolean
  selectedAssetIds?: string[]
  recommendationContext?: CreationRecommendationContext
}>(), {
  selectable: false,
  selectedAssetIds: () => [],
  recommendationContext: undefined,
})
const emit = defineEmits<{
  'request-login': []
  'selection-change': [assetIds: string[]]
}>()

const grassland = useGrassland()
const auth = useAuth()

type LibraryTab = 'recommend' | 'personal' | 'merchant' | 'public'
const activeTab = ref<LibraryTab>('personal')
const identities = ref<IdentityProfile[]>([])
const assets = ref<ContentAsset[]>([])
const notice = ref('')
const showUpload = ref(false)
/** 商家子模式：自己管理（默认）还是看被授权的推荐官视角。 */
const grantedView = ref(false)
const stores = ref<Store[]>([])
const storeScopes = ref<StoreAccessScope[]>([])
const organizationScopes = ref<OrganizationAccessScope[]>([])
const selectedOrganizationId = ref('')
/** Empty means organization-level merchant assets; otherwise the selected store scope. */
const selectedStoreId = ref('')
const selectedIds = ref<string[]>([...props.selectedAssetIds])
/**
 * 推荐 tab：id → 分数/理由（可解释排序）；query 存服务端实际采用的检索上下文，
 * semantic 存语义运行元数据（任务书 #33：not_requested/applied/fallback）。
 */
const recommendationScores = ref<Record<string, {
  score: number; ruleScore: number; semanticScore?: number; reasons: string[]
}>>({})
const recommendationQuery = ref<{ platform: string; contentForm: string; terms: string[]; sourceTitle?: string } | null>(null)
const recommendationSemantic = ref<SemanticRecommendationMetadata | null>(null)
/** 语义搜索输入（提交时 trim；空 = 不带 query，任务模式回落权威任务文本）。 */
const semanticQuery = ref('')

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
/** 当前账号在所选组织的成员角色（identity 权威，owner/admin/member；无成员行时为 null）。 */
const organizationRole = computed(() =>
  organizationScopes.value.find((scope) => scope.organizationId === selectedOrganizationId.value)?.role ?? null)
/** org admin/member 粒度：组织级商家素材的管理（上传/删除/授权）要求 org owner/admin。 */
const isOrganizationAdmin = computed(() =>
  hasOrganizationManagement.value
  && (organizationRole.value === 'owner' || organizationRole.value === 'admin'))
const canManageCurrentMerchantScope = computed(() => {
  if (!selectedOrganizationId.value || grantedView.value) return false
  if (!selectedStoreId.value) return isOrganizationAdmin.value
  return managerStoreScopes.value.some((scope) =>
    scope.organizationId === selectedOrganizationId.value
      && scope.storeId === selectedStoreId.value) || isOrganizationAdmin.value
})
const canReviewPublic = computed(() => auth.hasBackendRole('content_reviewer'))
/** 组织级素材批量迁移入口：商家 tab 管理视角 + 组织级范围 + org admin + 本 org 有可选门店。 */
const canMigrateOrgAssets = computed(() =>
  activeTab.value === 'merchant' && !grantedView.value
  && !selectedStoreId.value && isOrganizationAdmin.value
  && selectedOrganizationId.value !== '' && stores.value.length > 0)
const migrationActive = ref(false)
const migrationTargetStoreId = ref('')
const migrationIds = ref<string[]>([])

watch(() => props.selectedAssetIds, (ids) => {
  selectedIds.value = [...ids]
}, { deep: true })

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
    const [list, scopes, orgScopeList] = await Promise.all([
      grassland.listIdentities(), grassland.listMyStoreScopes(),
      grassland.listMyOrganizationScopes(),
    ])
    if (list) identities.value = list
    if (Array.isArray(scopes)) storeScopes.value = scopes
    if (Array.isArray(orgScopeList)) organizationScopes.value = orgScopeList
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

/** 语义搜索提交：只刷新推荐数据，不动其它 tab 的列表与选择（任务书 #33）。 */
async function searchRecommendations(): Promise<void> {
  notice.value = ''
  assets.value = []
  recommendationScores.value = {}
  recommendationQuery.value = null
  recommendationSemantic.value = null
  const context = props.recommendationContext
  const query = semanticQuery.value.trim()
  const result = await grassland.recommendContentAssets({
    applicationId: context?.applicationId,
    taskId: context?.taskId,
    platform: context?.platform,
    contentForm: context?.contentForm,
    keywords: context?.keywords,
    query: query || undefined,
  })
  if (result) {
    assets.value = result.items
    recommendationQuery.value = { ...result.query, sourceTitle: result.sourceTitle }
    recommendationSemantic.value = result.query.semantic ?? null
    recommendationScores.value = Object.fromEntries(
      result.items.map((item) => [item.id, {
        score: item.score, ruleScore: item.ruleScore,
        semanticScore: item.semanticScore, reasons: item.reasons,
      }]))
  }
}

async function refresh(): Promise<void> {
  notice.value = ''
  assets.value = []
  recommendationScores.value = {}
  recommendationQuery.value = null
  recommendationSemantic.value = null
  if (activeTab.value === 'recommend') {
    await searchRecommendations()
    return
  }
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
    // 读取粒度：组织级列表 merchant 身份即可（member 只读）；门店列表加显式门店范围。
    const sameOrgMerchant = hasOrganizationManagement.value
    const canViewScope = !selectedStoreId.value
      ? sameOrgMerchant
      : sameOrgMerchant || managerStoreScopes.value.some((scope) =>
        scope.organizationId === selectedOrganizationId.value && scope.storeId === selectedStoreId.value)
    if (canViewScope) {
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
  const libraryType: ContentLibraryType =
    activeTab.value === 'public' ? 'public' : activeTab.value === 'recommend' ? 'personal' : activeTab.value
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
    if (selectedIds.value.includes(asset.id)) toggleSelection(asset.id)
    notice.value = '已删除'
    await refresh()
  }
}

function canSelect(asset: ContentAsset): boolean {
  if (!props.selectable) return false
  if (activeTab.value === 'merchant' && !grantedView.value) return false
  return selectedIds.value.includes(asset.id) || selectedIds.value.length < 50
}
function toggleSelection(assetId: string): void {
  const next = selectedIds.value.includes(assetId)
    ? selectedIds.value.filter((id) => id !== assetId)
    : [...selectedIds.value, assetId]
  if (next.length > 50) return
  selectedIds.value = next
  emit('selection-change', [...next])
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

function toggleMigrationSelection(assetId: string): void {
  migrationIds.value = migrationIds.value.includes(assetId)
    ? migrationIds.value.filter((id) => id !== assetId)
    : [...migrationIds.value, assetId]
}

function resetMigration(): void {
  migrationActive.value = false
  migrationTargetStoreId.value = ''
  migrationIds.value = []
}

async function runMigration(): Promise<void> {
  const result = await grassland.migrateContentAssetsToStore({
    storeId: migrationTargetStoreId.value,
    assetIds: [...migrationIds.value],
  })
  if (!result) return
  const failed = result.items.filter((item) => !item.moved).length
  resetMigration()
  await refresh()
  // refresh() 开头会清 notice，迁移结果必须在其后再写入，否则用户永远看不到。
  notice.value = failed === 0
    ? `已迁移 ${result.moved} 项素材到门店`
    : `已迁移 ${result.moved} 项，${failed} 项不可迁移（可能已在门店或状态已变化）`
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
    <p v-if="selectable" class="lib-selection" aria-live="polite">
      已选择 {{ selectedIds.length }} / 50 项创作素材
    </p>

    <nav class="lib-tabs" role="tablist">
      <button v-if="authenticated" type="button" role="tab" :aria-selected="activeTab === 'recommend'"
        :class="{ active: activeTab === 'recommend' }" @click="selectTab('recommend')">智能推荐</button>
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

    <p v-if="activeTab === 'recommend' && recommendationQuery" class="lib-recommend-meta" aria-live="polite">
      {{ recommendationQuery.sourceTitle ? `按任务「${recommendationQuery.sourceTitle}」` : '按当前创作上下文' }}
      推荐（{{ recommendationQuery.platform || '未指定平台'
        }}{{ recommendationQuery.terms.length > 0 ? ` · 关键词：${recommendationQuery.terms.slice(0, 6).join('、')}` : '' }}）
    </p>

    <!-- 任务书 #33：语义搜索（仅智能推荐 tab；空 query=按任务上下文推荐）。 -->
    <form v-if="activeTab === 'recommend'" class="lib-semantic-search" data-testid="semantic-search"
      @submit.prevent="searchRecommendations">
      <input v-model="semanticQuery" type="search" data-testid="semantic-query" maxlength="500"
        placeholder="用一句话描述想要的素材（如：门店开业宣传海报）" aria-label="语义搜索素材">
      <button type="button" :disabled="grassland.loading.value" @click="searchRecommendations">搜索</button>
    </form>
    <p v-if="activeTab === 'recommend' && recommendationSemantic?.status === 'fallback'"
      class="lib-semantic-fallback" aria-live="polite">
      {{ recommendationSemantic.message ?? '语义检索暂不可用，已按规则排序' }}
    </p>

    <!-- 上传区（个人/商家/审核员，公共需 content_reviewer；组织级商家素材仅 org owner/admin） -->
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

    <!-- 组织级 legacy 素材批量迁移到门店（Slice 14 收尾）：仅 org admin 在组织级范围可见。 -->
    <div v-if="canMigrateOrgAssets" class="lib-migrate">
      <button v-if="!migrationActive" type="button" @click="migrationActive = true">迁移组织素材到门店</button>
      <template v-else>
        <p class="lib-migrate-hint">
          勾选要迁移的组织级素材，选择目标门店——迁移生成新版本快照（组织级形态留档），门店素材改由门店 MANAGER 管理。
        </p>
        <div class="lib-migrate-row">
          <label>目标门店
            <select v-model="migrationTargetStoreId">
              <option value="" disabled>选择门店</option>
              <option v-for="store in stores" :key="store.id" :value="store.id">{{ store.name }}</option>
            </select>
          </label>
          <span class="lib-migrate-count">已选 {{ migrationIds.length }} 项</span>
          <button type="button" :disabled="!migrationTargetStoreId || migrationIds.length === 0
            || grassland.loading.value" @click="runMigration">执行迁移</button>
          <button type="button" @click="resetMigration">取消</button>
        </div>
      </template>
    </div>

    <ul v-if="assets.length > 0" class="lib-grid">
      <li v-for="asset in assets" :key="asset.id" class="lib-card">
        <div class="lib-card-head">
          <label v-if="selectable && (activeTab !== 'merchant' || grantedView)" class="lib-select">
            <input type="checkbox" :checked="selectedIds.includes(asset.id)" :disabled="!canSelect(asset)"
              :aria-label="`选择素材：${asset.title}`" @change="toggleSelection(asset.id)" />
            <span class="lib-title">{{ asset.title }}</span>
          </label>
          <span v-else class="lib-title">{{ asset.title }}</span>
          <span class="lib-cat">{{ categoryLabel(asset.category) }}</span>
        </div>
        <p v-if="asset.mimeType || asset.sizeBytes" class="lib-meta">
          {{ asset.mimeType }} · {{ formatSize(asset.sizeBytes) }}
        </p>
        <p v-if="asset.tags.length > 0" class="lib-tags">{{ asset.tags.join('、') }}</p>
        <template v-if="activeTab === 'recommend' && recommendationScores[asset.id]">
          <p class="lib-score">
            匹配度 {{ recommendationScores[asset.id].score }} · 规则 {{ recommendationScores[asset.id].ruleScore
            }}<template v-if="recommendationScores[asset.id].semanticScore != null">
              · 语义 {{ recommendationScores[asset.id].semanticScore }}
            </template>
          </p>
          <p v-if="recommendationScores[asset.id].reasons.length > 0" class="lib-reasons">
            {{ recommendationScores[asset.id].reasons.join(' · ') }}
          </p>
        </template>
        <p v-if="asset.source" class="lib-source">来源：{{ asset.source }}</p>
        <p v-if="asset.storeId" class="lib-source">
          范围：{{ stores.find((store) => store.id === asset.storeId)?.name || '门店素材' }}
        </p>
        <label v-if="migrationActive && !asset.storeId" class="lib-select lib-migrate-check">
          <input type="checkbox" :checked="migrationIds.includes(asset.id)"
            :aria-label="`迁移素材到门店：${asset.title}`" @change="toggleMigrationSelection(asset.id)" />
          <span>迁移此项</span>
        </label>
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
      {{ activeTab === 'recommend' ? '暂无可推荐的素材——先在个人/商家/公共库上传或等待商家授权。'
        : activeTab === 'public' ? '暂无公共素材。' : '还没有素材，点「添加素材」上传第一份。' }}
    </p>
  </section>
</template>

<style scoped>
.library { display: flex; flex-direction: column; gap: 12px; }
.lib-alert { margin: 0; padding: 6px 10px; border-radius: 6px; font-size: 12px; }
.lib-err { background: color-mix(in srgb, var(--color-danger) 14%, transparent); color: var(--color-danger); }
.lib-ok { background: color-mix(in srgb, var(--color-success) 14%, transparent); color: var(--color-success); }
.lib-tabs { display: flex; gap: 4px; flex-wrap: wrap; }
.lib-selection { margin: 0; color: var(--color-text-secondary); font-size: 12px; }
.lib-tabs button { padding: 6px 14px; border: 1px solid var(--color-border); background: transparent; color: var(--color-text); border-radius: 6px; cursor: pointer; font-size: 13px; }
.lib-tabs button.active { border-color: var(--color-accent); background: color-mix(in srgb, var(--color-accent) 12%, transparent); }
.lib-actions { display: flex; gap: 8px; }
.lib-scope { display: flex; align-items: center; gap: 8px; font-size: 12px; }
.lib-scope select { padding: 4px 8px; border: 1px solid var(--color-border); border-radius: 5px; background: var(--color-surface); color: var(--color-text); }
.lib-migrate { display: flex; flex-direction: column; gap: 6px; padding: 8px 10px; border: 1px dashed var(--color-border); border-radius: 8px; }
.lib-migrate-hint { margin: 0; font-size: 11px; opacity: 0.7; }
.lib-migrate-row { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; font-size: 12px; }
.lib-migrate-row select { padding: 4px 8px; border: 1px solid var(--color-border); border-radius: 5px; background: var(--color-surface); color: var(--color-text); }
.lib-migrate-row button { padding: 5px 12px; border: 1px solid var(--color-border); background: transparent; color: var(--color-text); border-radius: 6px; cursor: pointer; font-size: 12px; }
.lib-migrate-row button:disabled { opacity: 0.5; cursor: not-allowed; }
.lib-migrate-count { opacity: 0.7; }
.lib-migrate-check { margin-top: 2px; font-size: 12px; opacity: 0.85; }
.lib-migrate-check input { width: 14px; height: 14px; margin: 0; accent-color: var(--color-accent); }
.lib-actions button { padding: 6px 14px; border: 1px solid var(--color-border); background: transparent; color: var(--color-text); border-radius: 6px; cursor: pointer; font-size: 13px; }
.lib-grid { list-style: none; margin: 0; padding: 0; display: grid; grid-template-columns: repeat(auto-fill, minmax(220px, 1fr)); gap: 10px; }
.lib-card { display: flex; flex-direction: column; gap: 4px; padding: 10px; border: 1px solid var(--color-border); border-radius: 8px; }
.lib-card-head { display: flex; align-items: center; gap: 6px; justify-content: space-between; }
.lib-select { display: flex; align-items: center; gap: 7px; min-width: 0; }
.lib-select input { width: 16px; height: 16px; margin: 0; accent-color: var(--color-accent); flex: 0 0 auto; }
.lib-title { font-size: 13px; font-weight: 500; word-break: break-all; }
.lib-cat { font-size: 11px; padding: 1px 6px; border-radius: 4px; background: var(--color-surface-strong); white-space: nowrap; }
.lib-meta, .lib-tags, .lib-source { margin: 0; font-size: 11px; opacity: 0.65; word-break: break-all; }
.lib-recommend-meta { margin: 0; font-size: 12px; color: var(--color-text-secondary); }
.lib-score { margin: 0; font-size: 11px; font-weight: 600; color: var(--color-accent); }
.lib-reasons { margin: 0; font-size: 11px; opacity: 0.75; }
.lib-card-actions { display: flex; gap: 4px; flex-wrap: wrap; margin-top: 4px; }
.lib-card-actions button { padding: 3px 10px; font-size: 12px; border: 1px solid var(--color-border); background: transparent; color: var(--color-text); border-radius: 6px; cursor: pointer; }
.lib-card-actions button:hover:not(:disabled) { border-color: var(--color-border-hover); background: var(--color-surface-hover); }
.lib-card-actions button:disabled { opacity: 0.5; cursor: not-allowed; }
.lib-semantic-search { display: flex; gap: 8px; }
.lib-semantic-search input { flex: 1; }
.lib-semantic-fallback {
  margin: 0; padding: 6px 10px; border-radius: 6px; font-size: 12px;
  background: color-mix(in srgb, var(--color-warning, #b36b00) 12%, transparent);
}
.lib-empty { margin: 0; font-size: 13px; opacity: 0.6; }
</style>
