<template>
  <div class="workspace-rail">
    <template v-if="authenticated">
      <section class="workspace-rail-card" aria-labelledby="workspace-usage-title">
        <div class="workspace-rail-head"><h3 id="workspace-usage-title">积分与用量</h3></div>
        <div class="workspace-rail-body">
          <div class="workspace-rail-facts">
            <div><dt>余额</dt><dd><span class="gl-num">{{ balance === null ? (creditsError ? '获取失败' : '…') : currentBalance }}</span> 次</dd></div>
          </div>
          <p class="gl-hint">生成成功后扣积分，失败不扣。</p>
          <RouterLink class="gl-btn-secondary" :to="{ name: 'settings' }">用量明细</RouterLink>
        </div>
      </section>

      <section class="workspace-rail-card" aria-labelledby="workspace-rail-assets-title">
        <div class="workspace-rail-head">
          <h3 id="workspace-rail-assets-title">最近素材</h3>
          <RouterLink class="gl-btn-secondary workspace-rail-compact" :to="{ name: 'assets' }">全部</RouterLink>
        </div>
        <div class="workspace-rail-body">
          <p v-if="assetsError" class="gl-hint" role="alert">素材加载失败，稍后在素材库重试。</p>
          <template v-else-if="recentAssets.length">
            <div v-for="asset in recentAssets" :key="asset.id" class="workspace-asset-row">
              <span class="workspace-asset-thumb" aria-hidden="true">{{ assetGlyph(asset) }}</span>
              <div class="workspace-asset-main">
                <p class="workspace-asset-name">{{ asset.title || '未命名素材' }}</p>
                <p class="gl-hint">{{ [assetKind(asset), relativeTime(asset.createdAt)].filter(Boolean).join(' · ') }}</p>
              </div>
            </div>
          </template>
          <p v-else class="gl-hint">{{ assetsLoading ? '正在载入素材…' : '还没有素材，去素材库上传第一批。' }}</p>
        </div>
      </section>
    </template>
    <section v-else class="workspace-rail-card" aria-labelledby="workspace-rail-login-title">
      <div class="workspace-rail-head"><h3 id="workspace-rail-login-title">积分与素材</h3></div>
      <div class="workspace-rail-body">
        <p class="gl-hint">登录后可查看积分余额、最近素材，并继续未完成的创作。</p>
        <button type="button" class="gl-btn-primary" @click="emit('request-login')">登录 / 注册</button>
      </div>
    </section>
  </div>
</template>
<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { useCredits } from '../../composables/useCredits'
import { useGrassland } from '../../composables/useGrassland'
import type { ContentAsset } from '../../types/grassland'

/**
 * 创作首页侧栏的账号态卡片（2026-10 重设计）：积分只展示真实余额（月度用量
 * 接口不存在，不伪造进度条）；最近素材取个人素材库前 3 条。匿名时收敛为登录引导。
 */
const props = defineProps<{ authenticated: boolean }>()
const emit = defineEmits<{ 'request-login': [] }>()

const { balance, currentBalance, error: creditsError, loadBalance } = useCredits()
const grassland = useGrassland()

const assets = ref<ContentAsset[]>([])
const assetsLoading = ref(false)
const assetsError = ref(false)
const recentAssets = computed(() => [...assets.value]
  .sort((a, b) => new Date(b.createdAt ?? 0).getTime() - new Date(a.createdAt ?? 0).getTime())
  .slice(0, 3))

onMounted(() => {
  if (!props.authenticated) return
  void loadBalance()
  assetsLoading.value = true
  grassland.listContentAssets({ libraryType: 'personal' })
    .then(result => { assets.value = result?.items ?? [] })
    .catch(() => { assetsError.value = true })
    .finally(() => { assetsLoading.value = false })
})

function assetGlyph(asset: ContentAsset): string {
  const type = (asset.mimeType || '').split('/')[0]
  if (type === 'image') return '图'
  if (type === 'video') return '视'
  if (type === 'audio') return '音'
  return '档'
}
function assetKind(asset: ContentAsset): string {
  const type = (asset.mimeType || '').split('/')[0]
  if (type === 'image') return '图片'
  if (type === 'video') return '视频'
  if (type === 'audio') return '音频'
  return '文档'
}
function relativeTime(iso: string | null): string {
  if (!iso) return ''
  const at = new Date(iso).getTime()
  if (!Number.isFinite(at)) return ''
  const minutes = Math.floor((Date.now() - at) / 60_000)
  if (minutes < 60) return '刚刚'
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours} 小时前`
  const days = Math.floor(hours / 24)
  if (days < 30) return `${days} 天前`
  return new Date(iso).toLocaleDateString('zh-CN')
}
</script>
