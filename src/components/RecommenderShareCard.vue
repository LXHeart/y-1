<template>
  <div class="share-card">
    <h3>我的推广链接</h3>
    <p class="gl-hint">
      消费者经你的专属链接下单，推荐归因自动锁定到你（免手输账号 ID）。
      向商家要套餐 ID，生成带归因的购买链接与二维码。
    </p>
    <div class="share-form">
      <input
        v-model="packageId"
        placeholder="输入商家提供的套餐 ID"
        @keyup.enter="generate"
      />
      <button type="button" :disabled="loading || !packageId.trim()" @click="generate">生成</button>
    </div>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p>

    <div v-if="pkg" class="share-result">
      <p class="share-package">
        <strong>{{ pkg.title }}</strong>
        <span>¥{{ (pkg.priceCents / 100).toFixed(2) }} · 推荐分成 {{ (pkg.recommenderShareBps / 100).toFixed(1) }}%</span>
      </p>
      <div class="copy-row">
        <input :value="shareUrl" readonly />
        <button type="button" @click="copy">{{ copied ? '已复制' : '复制链接' }}</button>
      </div>
      <img v-if="qr" class="share-qr" :src="qr" alt="推荐官专属购买二维码" />
      <p class="gl-hint">消费者扫码或打开链接 → 套餐已带出、归因已锁定，直接下单。</p>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import QRCode from 'qrcode'
import { useCommerce } from '../composables/useCommerce'
import { useAuth } from '../composables/useAuth'
import type { CommercePackage } from '../types/commerce'

const commerce = useCommerce()
const { currentUser } = useAuth()

const packageId = ref('')
const pkg = ref<CommercePackage | null>(null)
const shareUrl = ref('')
const qr = ref('')
const loading = ref(false)
const error = ref('')
const copied = ref(false)

async function generate(): Promise<void> {
  const id = packageId.value.trim()
  if (!id || loading.value) return
  loading.value = true
  error.value = ''
  copied.value = false
  try {
    pkg.value = await commerce.getPackage(id)
    if (!pkg.value) throw new Error('套餐不存在')
    if (!currentUser.value?.id) throw new Error('登录后才能生成专属归因链接')
    const url = new URL(window.location.origin + window.location.pathname)
    url.searchParams.set('view', 'commerce')
    url.searchParams.set('package', id)
    url.searchParams.set('recommender', currentUser.value.id)
    shareUrl.value = url.toString()
    qr.value = await QRCode.toDataURL(shareUrl.value, { width: 220, margin: 1 })
  } catch (cause) {
    pkg.value = null
    shareUrl.value = ''
    qr.value = ''
    error.value = cause instanceof Error ? cause.message : '推广链接生成失败'
  } finally {
    loading.value = false
  }
}

async function copy(): Promise<void> {
  await navigator.clipboard.writeText(shareUrl.value)
  copied.value = true
}
</script>
