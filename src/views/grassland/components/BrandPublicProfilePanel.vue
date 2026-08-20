<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useGrassland } from '../../../composables/useGrassland'
import type { PublicBrandProfile } from '../../../types/grassland'

/**
 * 组织品牌资料公开卡片（缺口清偿之六，#32 D9）：挂 StorePublicProfilePanel 之后，
 * 消费 `GET /api/organizations/{orgId}/public-brand-profile`。
 *
 * - 按需拉取、与门店公开资料解耦互不打挂；任务上下文切换随 organizationId 重拉。
 * - 全字段空（商家未填资料）→ 整卡不渲染（公开页不出空壳）。
 * - logoUrl 为短时 presigned URL（fail-soft 置 null），只即时渲染不持久化。
 */
const props = defineProps<{
  /** 选中任务所属组织 id；为空则不渲染。 */
  organizationId: string | null
}>()

const grassland = useGrassland()

const profile = ref<PublicBrandProfile | null>(null)
const loading = ref(false)
let loadSeq = 0

async function load(organizationId: string): Promise<void> {
  const seq = ++loadSeq
  loading.value = true
  const result = await grassland.getPublicBrandProfile(organizationId)
  // 快速切换任务时丢弃过期响应。
  if (seq !== loadSeq || props.organizationId !== organizationId) return
  profile.value = result
  loading.value = false
}

watch(() => props.organizationId, (organizationId) => {
  profile.value = null
  if (organizationId) void load(organizationId)
}, { immediate: true })

const hasContent = computed(() => {
  const value = profile.value
  return Boolean(value && (value.brandName || value.description || value.industry || value.logoUrl))
})

const INDUSTRY_LABELS: Record<string, string> = {
  catering: '餐饮', retail: '零售', beauty: '美业', education: '教育',
  e_commerce: '电商', healthcare: '医疗健康', finance: '金融', real_estate: '房产',
  travel: '文旅', children: '亲子', other: '其他',
}
const industryLabel = computed(() => {
  const industry = profile.value?.industry
  return industry ? INDUSTRY_LABELS[industry] ?? industry : ''
})
</script>

<template>
  <article v-if="organizationId && hasContent" id="gl-brand-public-profile" class="gl-card gl-card-wide">
    <h3>品牌资料</h3>
    <div class="brand-row">
      <img
        v-if="profile?.logoUrl"
        :src="profile.logoUrl"
        class="brand-logo"
        alt="品牌 Logo"
        loading="lazy"
        decoding="async"
        @error="profile && (profile.logoUrl = null)"
      />
      <dl class="brand-grid">
        <div v-if="profile?.brandName" class="gl-profile-row">
          <dt>品牌名称</dt>
          <dd>{{ profile.brandName }}</dd>
        </div>
        <div v-if="industryLabel" class="gl-profile-row">
          <dt>经营分类</dt>
          <dd>{{ industryLabel }}</dd>
        </div>
        <div v-if="profile?.description" class="gl-profile-row">
          <dt>品牌简介</dt>
          <dd>{{ profile.description }}</dd>
        </div>
      </dl>
    </div>
  </article>
</template>

<style scoped>
.brand-row {
  display: flex;
  gap: 16px;
  align-items: flex-start;
}

.brand-logo {
  width: 72px;
  height: 72px;
  border-radius: 10px;
  border: 1px solid var(--gl-border, #e5e7eb);
  object-fit: cover;
  flex: 0 0 auto;
}

.brand-grid {
  flex: 1;
  min-width: 0;
}

@media (max-width: 640px) {
  .brand-row {
    flex-direction: column;
  }
}
</style>
