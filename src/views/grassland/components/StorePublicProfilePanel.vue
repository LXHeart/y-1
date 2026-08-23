<template>
  <article v-if="storeId" id="gl-store-public-profile" class="gl-tile gl-tile-wide">
    <h3>门店公开资料</h3>
    <p v-if="loading" class="gl-hint">加载中…</p>
    <p v-else-if="error" class="gl-hint">{{ error }}</p>
    <p v-else-if="!profile" class="gl-empty">该门店暂无公开资料</p>
    <dl v-else class="gl-store-profile-grid">
      <div class="gl-profile-row">
        <dt>门店名称</dt>
        <dd>{{ profile.storeName }}</dd>
      </div>
      <div v-if="addressText" class="gl-profile-row">
        <dt>地址</dt>
        <dd>{{ addressText }}</dd>
      </div>
      <div v-if="profile.phone" class="gl-profile-row">
        <dt>联系电话</dt>
        <dd>{{ profile.phone }}</dd>
      </div>
      <div v-if="businessHoursText.length > 0" class="gl-profile-row">
        <dt>营业时间</dt>
        <dd>
          <span v-for="line in businessHoursText" :key="line" class="gl-block-line">{{ line }}</span>
        </dd>
      </div>
      <div v-if="profile.categories.length > 0" class="gl-profile-row">
        <dt>主营品类</dt>
        <dd>
          <span v-for="item in profile.categories" :key="item" class="gl-tag">{{ item }}</span>
        </dd>
      </div>
      <div v-if="profile.signatureItems.length > 0" class="gl-profile-row">
        <dt>特色产品/服务</dt>
        <dd>
          <span v-for="item in profile.signatureItems" :key="item" class="gl-block-line">{{ item }}</span>
        </dd>
      </div>
      <div v-if="profile.sellingPoints.length > 0" class="gl-profile-row">
        <dt>推荐卖点</dt>
        <dd>
          <span v-for="item in profile.sellingPoints" :key="item" class="gl-block-line">{{ item }}</span>
        </dd>
      </div>
      <div v-if="profile.averageSpendCents != null || profile.priceRange" class="gl-profile-row">
        <dt>消费参考</dt>
        <dd>
          <template v-if="profile.averageSpendCents != null">人均 {{ formatYuan(profile.averageSpendCents) }}</template>
          <template v-if="profile.averageSpendCents != null && profile.priceRange"> · </template>
          <template v-if="profile.priceRange">{{ profile.priceRange }}</template>
        </dd>
      </div>
      <div v-if="profile.visitNotes" class="gl-profile-row">
        <dt>到店提示</dt>
        <dd>{{ profile.visitNotes }}</dd>
      </div>
      <div v-if="profile.description" class="gl-profile-row">
        <dt>门店描述</dt>
        <dd>{{ profile.description }}</dd>
      </div>
      <div v-if="profile.brandTone" class="gl-profile-row">
        <dt>品牌语气</dt>
        <dd>{{ profile.brandTone }}</dd>
      </div>
      <div v-if="profile.mustEmphasize.length > 0" class="gl-profile-row">
        <dt>必须强调</dt>
        <dd>
          <span v-for="item in profile.mustEmphasize" :key="item" class="gl-block-line">{{ item }}</span>
        </dd>
      </div>
      <div v-if="profile.forbiddenPhrases.length > 0" class="gl-profile-row">
        <dt>禁止表达</dt>
        <dd>
          <span v-for="item in profile.forbiddenPhrases" :key="item" class="gl-block-line">{{ item }}</span>
        </dd>
      </div>
      <div v-if="profile.allowedTags.length > 0" class="gl-profile-row">
        <dt>可使用标签</dt>
        <dd>
          <span v-for="item in profile.allowedTags" :key="item" class="gl-tag">{{ item }}</span>
        </dd>
      </div>
    </dl>
  </article>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { StorePublicProfile } from '../../../types/grassland'
import { formatYuan } from '../../../lib/money'

const props = defineProps<{
  /** 当前选中任务的门店 id；为空则不渲染。 */
  storeId: string | null
  profile: StorePublicProfile | null
  loading: boolean
  error: string
}>()

const WEEKDAYS = ['', '周一', '周二', '周三', '周四', '周五', '周六', '周日']

const addressText = computed(() => {
  if (!props.profile?.address) return ''
  try {
    const parsed = JSON.parse(props.profile.address) as Record<string, unknown>
    return ['province', 'city', 'district', 'address']
      .map((key) => (typeof parsed[key] === 'string' ? parsed[key] : ''))
      .filter(Boolean)
      .join(' ')
  } catch {
    return ''
  }
})

const businessHoursText = computed<string[]>(() => {
  if (!props.profile?.businessHours) return []
  try {
    const parsed = JSON.parse(props.profile.businessHours) as unknown
    if (!Array.isArray(parsed)) return []
    return parsed
      .filter((item): item is Record<string, unknown> => Boolean(item) && typeof item === 'object')
      .map((item) => {
        const day = typeof item.dayOfWeek === 'number' ? WEEKDAYS[item.dayOfWeek] ?? '' : ''
        const open = typeof item.openTime === 'string' ? item.openTime : ''
        const close = typeof item.closeTime === 'string' ? item.closeTime : ''
        return [day, open && close ? `${open}–${close}` : ''].filter(Boolean).join(' ')
      })
      .filter(Boolean)
  } catch {
    return []
  }
})
</script>

<style scoped>
h3 { margin: 0; font-size: var(--text-base); font-weight: 700; letter-spacing: -0.01em; }

.gl-store-profile-grid {
  display: grid;
  gap: 8px;
  margin: 0;
}
.gl-profile-row {
  display: grid;
  grid-template-columns: 120px 1fr;
  gap: 8px;
}
.gl-profile-row dt {
  color: var(--color-text-muted);
}
.gl-profile-row dd {
  margin: 0;
}
.gl-block-line {
  display: block;
}
.gl-tag {
  display: inline-block;
  margin: 0 6px 4px 0;
  padding: 1px 8px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  font-size: 12px;
}
</style>
