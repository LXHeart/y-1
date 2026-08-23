<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useGrassland } from '../../../composables/useGrassland'
import { STORE_MEDIA_KINDS, STORE_MEDIA_KIND_META } from '../../../types/grassland'
import type { StoreMediaGroups, StoreMediaItem } from '../../../types/grassland'

/**
 * 门店公开媒体画廊（任务书 #42 Stage 3，D4/D5）：挂 StorePublicProfilePanel 之后。
 *
 * - 按需拉 `GET /api/stores/{storeId}/public-media`（与 public-profile 解耦，互不打挂）。
 * - 图片 `<img loading="lazy" decoding="async">` + 固定宽高比占位防布局抖动；
 *   视频 `<video controls preload="none">` 不预载。
 * - 单项 onerror（加载失败/URL 过期）→ **重拉一次** public-media 换新 URL（D5 URL 自愈）；
 *   重拉后再失败 → 该项显示占位，不连累其余媒体。重拉本身失败（如上游 503）时保留
 *   旧 groups：旧项逐个走 @error 落 failedMediaIds 占位，不整卡蒸发。
 * - 四组全空 → 整卡不渲染（公开页不该出现空壳卡片）。
 */

const props = defineProps<{
  /** 当前选中任务的门店 id；为空则不渲染。 */
  storeId: string | null
}>()

const grassland = useGrassland()

const groups = ref<StoreMediaGroups<StoreMediaItem> | null>(null)
/** 已耗尽重拉机会的媒体 id —— 再 onerror 直接落占位，避免无限重拉循环。 */
const failedMediaIds = ref<Set<string>>(new Set())
let retryUsed = false
let loadSeq = 0

async function load(storeId: string): Promise<void> {
  const seq = ++loadSeq
  const result = await grassland.getStorePublicMedia(storeId)
  // 快速切换任务时丢弃过期响应。
  if (seq !== loadSeq || props.storeId !== storeId) return
  // 仅拉取成功时覆盖 groups；重拉失败（如上游 503）保留旧 groups，旧项逐个走
  // @error 落 failedMediaIds 占位，避免整卡蒸发且 retryUsed 已耗无兜底。
  if (result) {
    groups.value = result.groups ?? null
  }
}

watch(() => props.storeId, (storeId) => {
  groups.value = null
  failedMediaIds.value = new Set()
  retryUsed = false
  if (storeId) void load(storeId)
}, { immediate: true })

/** 单项资源加载失败：第一次重拉整端点换短时 URL；重拉后仍失败则该项落占位。 */
function handleMediaError(item: StoreMediaItem): void {
  if (!retryUsed && props.storeId) {
    retryUsed = true
    void load(props.storeId)
    return
  }
  failedMediaIds.value = new Set([...failedMediaIds.value, item.mediaId])
}

const hasAny = computed(() => groups.value !== null
  && STORE_MEDIA_KINDS.some((kind) => (groups.value?.[kind]?.length ?? 0) > 0))

const isVideo = (item: StoreMediaItem): boolean =>
  typeof item.mimeType === 'string' && item.mimeType.startsWith('video/')
</script>

<template>
  <article
    v-if="storeId && hasAny && groups"
    id="gl-store-media-gallery"
    class="gl-tile gl-tile-wide"
  >
    <h3>门店媒体</h3>
    <template v-for="kind in STORE_MEDIA_KINDS" :key="kind">
      <section v-if="groups[kind].length > 0" class="gl-media-group">
        <h4>{{ STORE_MEDIA_KIND_META[kind].label }}</h4>
        <div class="gl-media-grid">
          <template v-for="item in groups[kind]" :key="item.mediaId">
            <div v-if="failedMediaIds.has(item.mediaId)" class="gl-media-broken" role="img" :aria-label="`${STORE_MEDIA_KIND_META[kind].label}预览不可用`">
              预览不可用
            </div>
            <video
              v-else-if="isVideo(item)"
              class="gl-media-video"
              :src="item.downloadUrl ?? undefined"
              controls
              preload="none"
              :aria-label="`${STORE_MEDIA_KIND_META[kind].label}第 ${item.position} 项`"
              @error="handleMediaError(item)"
            />
            <div v-else class="gl-media-frame">
              <img
                :src="item.downloadUrl ?? undefined"
                :alt="`${STORE_MEDIA_KIND_META[kind].label}第 ${item.position} 项`"
                loading="lazy"
                decoding="async"
                @error="handleMediaError(item)"
              />
            </div>
          </template>
        </div>
      </section>
    </template>
  </article>
</template>

<style scoped>
h3 { margin: 0; font-size: var(--text-base); font-weight: 700; letter-spacing: -0.01em; }

.gl-media-group {
  margin-top: 12px;
}

.gl-media-group h4 {
  margin: 0 0 8px 0;
  font-size: 14px;
  font-weight: 500;
}

.gl-media-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
  gap: 8px;
}

/* 固定宽高比占位：图片未加载/加载中不引起布局抖动。 */
.gl-media-frame {
  aspect-ratio: 4 / 3;
  background: var(--surface-muted);
  border-radius: 6px;
  overflow: hidden;
}

.gl-media-frame img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.gl-media-video {
  width: 100%;
  aspect-ratio: 16 / 9;
  background: #111827;
  border-radius: 6px;
}

.gl-media-broken {
  aspect-ratio: 4 / 3;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--surface-muted);
  border: 1px dashed var(--color-border);
  border-radius: 6px;
  color: var(--color-text-muted);
  font-size: 12px;
}
</style>
