<template>
  <div class="public-assets-panel">
    <section class="public-generation" aria-labelledby="public-generation-title">
      <div class="panel-toolbar"><div><h3 id="public-generation-title">AI 批量生产</h3><p>逐张生成并提交到待审队列，单张失败不影响其他结果。</p></div></div>
      <form class="public-generation-form" data-testid="public-asset-generation-form" @submit.prevent="generatePublicAssets">
        <label>素材类型<select v-model="publicGeneration.kind"><option value="icon">图标</option><option value="decoration">装饰元素</option><option value="background">背景</option><option value="mood">氛围图</option></select></label>
        <label>主题<input v-model="publicGeneration.theme" type="text" maxlength="100" placeholder="例如：夏日饮品" required></label>
        <label>视觉风格<input v-model="publicGeneration.style" type="text" maxlength="100" placeholder="选填，例如：清爽扁平"></label>
        <label>数量<input v-model.number="publicGeneration.count" type="number" min="1" max="12" required></label>
        <label>有效期<input v-model="publicGeneration.validUntil" type="datetime-local" required></label>
        <button class="approve-btn generation-submit" type="submit" :disabled="publicGenerating">{{ publicGenerating ? '生成中...' : '开始生成' }}</button>
      </form>
      <p v-if="publicGenerationError" class="error-msg" role="alert">{{ publicGenerationError }}</p>
      <div v-if="publicGenerationResult" class="generation-results" aria-live="polite">
        <strong>本批成功 {{ publicGenerationResult.okCount }} / {{ publicGenerationResult.items.length }}</strong>
        <ol><li v-for="item in publicGenerationResult.items" :key="item.index" :class="item.ok ? 'generation-ok' : 'generation-failed'"><span>第 {{ item.index }} 张</span><a v-if="item.ok && item.assetId" :href="`#public-asset-${item.assetId}`">{{ item.assetId }}</a><span v-else>{{ item.errorReason || '生成失败' }}</span></li></ol>
      </div>
    </section>
    <section aria-labelledby="public-review-title">
      <div class="panel-toolbar"><div><h3 id="public-review-title">待审公共素材</h3><p>通过后进入公共素材库，驳回必须填写原因。</p></div><form class="review-search" @submit.prevent="submitPublicAssetSearch"><input v-model="publicAssetSearch" type="search" maxlength="100" placeholder="搜索标题或标签"><button class="refresh-btn" type="submit" :disabled="publicAssetsLoading">搜索</button></form></div>
      <p v-if="publicAssetsError" class="error-msg" role="alert">{{ publicAssetsError }}</p>
      <div v-if="publicAssetsLoading" class="loading-state">加载中...</div>
      <div v-else-if="publicAssetReviews.length" class="public-review-grid">
        <article v-for="asset in publicAssetReviews" :id="`public-asset-${asset.id}`" :key="asset.id" class="public-review-item">
          <div class="public-asset-preview"><img v-if="publicAssetPreviewUrls[asset.id]" :src="publicAssetPreviewUrls[asset.id]" :alt="asset.title"><span v-else>暂无预览</span></div>
          <div class="public-asset-body"><div class="public-asset-heading"><h4>{{ asset.title }}</h4><span class="type-tag">{{ publicAssetCategoryLabel(asset.category) }}</span></div><p>{{ asset.tags.join(' · ') || '无标签' }}</p><p class="td-time">有效至 {{ formatDateTime(asset.validUntil || null) }}</p><label>审核备注<input v-model="publicAssetReviewNotes[asset.id]" class="field-input" type="text" maxlength="500" placeholder="驳回时必填"></label><div class="review-actions"><button class="approve-btn" type="button" :disabled="reviewingPublicAssetIds.has(asset.id)" @click="reviewPublicAsset(asset, 'approve')">通过</button><button class="reject-btn" type="button" :disabled="reviewingPublicAssetIds.has(asset.id)" @click="reviewPublicAsset(asset, 'reject')">驳回</button></div></div>
        </article>
      </div>
      <p v-else class="td-empty">暂无待审核公共素材</p>
      <OpsPagination v-if="publicAssetsTotal > 0" :total="publicAssetsTotal" :limit="PAGE_SIZE" :offset="publicAssetsOffset" @change="changePublicAssetsPage" />
    </section>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useGrassland } from '../../../composables/useGrassland'
import type { ContentAsset, ContentAssetCategory, PublicAssetBatchGenerateResult, PublicAssetGenerationKind } from '../../../types/grassland'
import OpsPagination from './OpsPagination.vue'

const PAGE_SIZE = 50
const grassland = useGrassland()
const publicAssetReviews = ref<ContentAsset[]>([])
const publicAssetSearch = ref('')
const publicAssetPreviewUrls = ref<Record<string, string>>({})
const publicAssetReviewNotes = ref<Record<string, string>>({})
const reviewingPublicAssetIds = ref(new Set<string>())
const publicAssetsLoading = ref(false)
const publicAssetsError = ref('')
const publicAssetsOffset = ref(0)
const publicAssetsTotal = ref(0)
const publicGenerating = ref(false)
const publicGenerationError = ref('')
const publicGenerationResult = ref<PublicAssetBatchGenerateResult | null>(null)

function defaultPublicAssetExpiry(): string {
  const date = new Date(Date.now() + 30 * 24 * 60 * 60 * 1000)
  const local = new Date(date.getTime() - date.getTimezoneOffset() * 60_000)
  return local.toISOString().slice(0, 16)
}
const publicGeneration = ref<{ kind: PublicAssetGenerationKind; theme: string; style: string; count: number; validUntil: string }>({ kind: 'icon', theme: '', style: '', count: 3, validUntil: defaultPublicAssetExpiry() })

async function loadPublicAssetReviews(): Promise<void> {
  publicAssetsLoading.value = true; publicAssetsError.value = ''
  const result = await grassland.listPendingPublicAssetReviews(publicAssetSearch.value, { limit: PAGE_SIZE, offset: publicAssetsOffset.value })
  if (!result) { publicAssetsError.value = grassland.error.value || '公共素材审核队列加载失败'; publicAssetsLoading.value = false; return }
  publicAssetReviews.value = [...result.items]
  publicAssetsTotal.value = result.total
  const previews = await Promise.all(result.items.map(async (asset) => { const download = await grassland.getContentAssetDownloadUrl(asset.id); return [asset.id, download?.downloadUrl || ''] as const }))
  publicAssetPreviewUrls.value = Object.fromEntries(previews); publicAssetsLoading.value = false
}

/** 筛选变化：提交新搜索时 offset 归零重载（任务 #3 分页契约）。 */
function submitPublicAssetSearch(): void {
  publicAssetsOffset.value = 0
  void loadPublicAssetReviews()
}

function changePublicAssetsPage(next: number): void {
  publicAssetsOffset.value = next
  void loadPublicAssetReviews()
}

async function generatePublicAssets(): Promise<void> {
  const input = publicGeneration.value; const theme = input.theme.trim(); const style = input.style.trim(); const validUntil = new Date(input.validUntil); const now = Date.now()
  if (!theme || theme.length > 100) { publicGenerationError.value = '主题长度需为 1-100 字符'; return }
  if (style.length > 100) { publicGenerationError.value = '视觉风格不能超过 100 字符'; return }
  if (!Number.isInteger(input.count) || input.count < 1 || input.count > 12) { publicGenerationError.value = '生成数量需为 1-12'; return }
  if (Number.isNaN(validUntil.getTime()) || validUntil.getTime() <= now || validUntil.getTime() > now + 90 * 24 * 60 * 60 * 1000) { publicGenerationError.value = '有效期必须在未来 90 天内'; return }
  publicGenerating.value = true; publicGenerationError.value = ''; publicGenerationResult.value = null
  const result = await grassland.batchGeneratePublicAssets({ kind: input.kind, theme, style: style || undefined, count: input.count, validUntil: validUntil.toISOString() })
  if (result) { publicGenerationResult.value = result; await loadPublicAssetReviews() } else publicGenerationError.value = grassland.error.value || '公共素材生成失败'
  publicGenerating.value = false
}

async function reviewPublicAsset(asset: ContentAsset, decision: 'approve' | 'reject'): Promise<void> {
  const note = (publicAssetReviewNotes.value[asset.id] || '').trim()
  if (decision === 'reject' && !note) { publicAssetsError.value = '驳回公共素材必须填写原因'; return }
  reviewingPublicAssetIds.value = new Set([...reviewingPublicAssetIds.value, asset.id]); publicAssetsError.value = ''
  const result = decision === 'approve' ? await grassland.approvePublicAsset(asset.id, asset.version, note || undefined) : await grassland.rejectPublicAsset(asset.id, asset.version, note)
  const nextReviewing = new Set(reviewingPublicAssetIds.value); nextReviewing.delete(asset.id); reviewingPublicAssetIds.value = nextReviewing
  if (!result) { publicAssetsError.value = grassland.error.value || '公共素材审核失败'; return }
  publicAssetReviews.value = publicAssetReviews.value.filter((item) => item.id !== asset.id); delete publicAssetReviewNotes.value[asset.id]; delete publicAssetPreviewUrls.value[asset.id]
}
function publicAssetCategoryLabel(category: ContentAssetCategory): string { return ({ scene: '场景', other: '图标/装饰' } as Partial<Record<ContentAssetCategory, string>>)[category] || category }
function formatDateTime(iso: string | null): string { if (!iso) return '-'; const date = new Date(iso); return Number.isNaN(date.getTime()) ? '-' : date.toLocaleString('zh-CN') }
onMounted(() => void loadPublicAssetReviews())
</script>

<style scoped>
.panel-toolbar { display: flex; align-items: flex-end; justify-content: space-between; gap: 16px; }
.panel-toolbar h3, .panel-toolbar p { margin: 0; }
.panel-toolbar h3 { font-size: 1rem; }
.review-search{display:flex;align-items:center;gap:8px}.review-search input{min-width:min(280px,56vw);min-height:34px;padding:6px 9px;border:1px solid var(--color-border);border-radius:6px;background:var(--color-surface);color:var(--color-text)}
.panel-toolbar p { margin-top: 4px; color: var(--color-text-muted); font-size: 0.82rem; }
.refresh-btn, .approve-btn, .reject-btn { min-height: 32px; padding: 0 12px; border-radius: var(--radius-sm); font-size: 0.78rem; cursor: pointer; }
.refresh-btn { border: 1px solid var(--color-border); background: transparent; color: var(--color-text-secondary); }
.refresh-btn:disabled, .approve-btn:disabled, .reject-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.approve-btn { border: 1px solid color-mix(in srgb, var(--color-success) 35%, transparent); background: color-mix(in srgb, var(--color-success) 8%, transparent); color: var(--color-success); }
.reject-btn { border: 1px solid color-mix(in srgb, var(--color-danger) 30%, transparent); background: color-mix(in srgb, var(--color-danger) 7%, transparent); color: var(--color-danger); }
.review-actions { display: flex; gap: 6px; }
.type-tag { display: inline-block; padding: 3px 7px; border: 1px solid var(--color-border); border-radius: var(--radius-pill); background: var(--surface-muted); white-space: nowrap; }
.loading-state { padding: var(--space-xl); text-align: center; color: var(--color-text-muted); font-size: 0.9rem; }
.error-msg { padding: var(--space-sm) var(--space-md); border-radius: var(--radius-sm); background: color-mix(in srgb, var(--color-danger) 10%, transparent); border: 1px solid color-mix(in srgb, var(--color-danger) 20%, transparent); color: var(--color-danger); font-size: 0.86rem; margin: 0; }
.td-time { white-space: nowrap; color: var(--color-text-muted); }
.td-empty { text-align: center; padding: var(--space-xl); color: var(--color-text-muted); }
.public-assets-panel,.public-assets-panel > section,.public-generation{display:grid;gap:16px}.public-assets-panel > section + section{padding-top:20px;border-top:1px solid var(--color-border)}.public-generation-form{display:grid;grid-template-columns:repeat(5,minmax(120px,1fr)) auto;align-items:end;gap:12px}.public-generation-form label,.public-asset-body label{display:grid;gap:5px;color:var(--color-text-muted);font-size:.78rem}.public-generation-form input,.public-generation-form select,.public-asset-body input{width:100%;min-height:36px;padding:6px 9px;border:1px solid var(--color-border);border-radius:6px;background:var(--color-surface);color:var(--color-text);box-sizing:border-box}.generation-submit{min-height:36px;white-space:nowrap}.generation-results{display:grid;gap:8px;padding:12px 0}.generation-results ol{display:grid;gap:6px;margin:0;padding-left:22px}.generation-results li{overflow-wrap:anywhere}.generation-results li span:first-child{display:inline-block;min-width:62px}.generation-ok a{color:var(--color-accent)}.generation-failed{color:var(--color-danger)}.public-review-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(280px,1fr));gap:12px}.public-review-item{display:grid;grid-template-columns:112px minmax(0,1fr);min-height:164px;border:1px solid var(--color-border);border-radius:8px;background:var(--color-surface);overflow:hidden}.public-asset-preview{display:grid;place-items:center;min-height:164px;background:var(--surface-muted);color:var(--color-text-muted);font-size:.76rem}.public-asset-preview img{width:100%;height:100%;object-fit:cover}.public-asset-body{display:grid;align-content:start;gap:8px;min-width:0;padding:12px}.public-asset-heading{display:flex;align-items:start;justify-content:space-between;gap:8px}.public-asset-heading h4,.public-asset-body p{margin:0}.public-asset-heading h4{min-width:0;font-size:.9rem;overflow-wrap:anywhere}.public-asset-body p{color:var(--color-text-muted);font-size:.76rem}.approve-btn:disabled,.reject-btn:disabled{opacity:.5;cursor:not-allowed}@media(max-width:640px){.public-generation-form{grid-template-columns:1fr}.public-review-item{grid-template-columns:88px minmax(0,1fr)}}@media(min-width:641px) and (max-width:1100px){.public-generation-form{grid-template-columns:repeat(2,minmax(180px,1fr))}}
</style>
