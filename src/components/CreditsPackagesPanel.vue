<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { request } from '../composables/grassland-http'
import { formatPrice } from '../composables/useCreditsPackages'

/**
 * 积分套餐管理面板（AI 套餐 v1，AdminView「积分套餐」tab）。
 * SKU 列表（版本/状态）+ 新建 + 调价（出新 version）+ 上下架 + 购买订单监控。
 */

interface AdminPackage {
  id: string
  name: string
  description: string
  status: string
  version: number
  priceCents: number
  creditsAmount: number
  note: string
}

interface AdminOrder {
  id: string
  accountId: string
  packageId: string
  priceCents: number
  creditsAmount: number
  status: string
}

const packages = ref<AdminPackage[]>([])
const orders = ref<AdminOrder[]>([])
const loading = ref(false)
const error = ref('')
const notice = ref('')

const form = ref({ name: '', description: '', priceYuan: '', creditsAmount: '', note: '' })
const repricing = ref<{ id: string, priceYuan: string, creditsAmount: string } | null>(null)

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    packages.value = await request<AdminPackage[]>('/api/admin/credits-packages')
    orders.value = await request<AdminOrder[]>('/api/admin/credits-purchase-orders')
  } catch (err: unknown) {
    error.value = err instanceof Error ? err.message : '加载失败'
  } finally {
    loading.value = false
  }
}

onMounted(() => void load())

async function createPackage(): Promise<void> {
  error.value = ''
  notice.value = ''
  try {
    await request('/api/admin/credits-packages', {
      method: 'POST',
      body: JSON.stringify({
        name: form.value.name.trim(),
        description: form.value.description.trim(),
        priceCents: Math.round(Number(form.value.priceYuan) * 100),
        creditsAmount: Number(form.value.creditsAmount),
      }),
    })
    notice.value = '已创建（draft 状态，需上架后用户可见）'
    form.value = { name: '', description: '', priceYuan: '', creditsAmount: '', note: '' }
    void load()
  } catch (err: unknown) {
    error.value = err instanceof Error ? err.message : '创建失败'
  }
}

async function reprice(pkg: AdminPackage): Promise<void> {
  if (!repricing.value) return
  error.value = ''
  notice.value = ''
  try {
    await request(`/api/admin/credits-packages/${pkg.id}`, {
      method: 'PUT',
      body: JSON.stringify({
        priceCents: Math.round(Number(repricing.value.priceYuan) * 100),
        creditsAmount: Number(repricing.value.creditsAmount),
      }),
    })
    notice.value = `已调价（v${pkg.version} → v${pkg.version + 1}）`
    repricing.value = null
    void load()
  } catch (err: unknown) {
    error.value = err instanceof Error ? err.message : '调价失败'
  }
}

async function setStatus(pkg: AdminPackage, status: string): Promise<void> {
  error.value = ''
  notice.value = ''
  try {
    await request(`/api/admin/credits-packages/${pkg.id}/status`, {
      method: 'PUT',
      body: JSON.stringify({ status }),
    })
    notice.value = `状态已更新为 ${status}`
    void load()
  } catch (err: unknown) {
    error.value = err instanceof Error ? err.message : '状态更新失败'
  }
}
</script>

<template>
  <div class="credits-packages-panel" data-test="credits-packages-panel">
    <div class="panel-toolbar">
      <button type="button" class="refresh-btn" :disabled="loading" @click="load">刷新</button>
      <span v-if="notice" class="notice">{{ notice }}</span>
      <span v-if="error" class="error" role="alert">{{ error }}</span>
    </div>

    <section aria-label="SKU 列表">
      <h4>积分套餐 SKU（调价 = 追加新版本，历史版本不可变）</h4>
      <table class="data-table">
        <thead>
          <tr>
            <th>名称</th><th>状态</th><th>版本</th><th>价格</th><th>面值</th><th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="!packages.length">
            <td colspan="6" class="muted">暂无套餐</td>
          </tr>
          <tr v-for="pkg in packages" :key="pkg.id">
            <td>{{ pkg.name }}</td>
            <td>
              <span class="status-tag" :class="pkg.status">{{ pkg.status }}</span>
            </td>
            <td>v{{ pkg.version }}</td>
            <td>¥{{ formatPrice(pkg.priceCents) }}</td>
            <td>{{ pkg.creditsAmount }} 积分</td>
            <td class="actions-cell">
              <template v-if="repricing?.id === pkg.id">
                <input v-model="repricing.priceYuan" type="text" placeholder="价格(元)" aria-label="新价格">
                <input v-model="repricing.creditsAmount" type="text" placeholder="面值" aria-label="新面值">
                <button type="button" @click="reprice(pkg)">确认调价</button>
                <button type="button" @click="repricing = null">取消</button>
              </template>
              <template v-else>
                <button v-if="pkg.status !== 'retired'" type="button"
                  @click="repricing = { id: pkg.id, priceYuan: String(pkg.priceCents / 100), creditsAmount: String(pkg.creditsAmount) }">
                  调价
                </button>
                <button v-if="pkg.status === 'draft'" type="button" @click="setStatus(pkg, 'active')">上架</button>
                <button v-if="pkg.status === 'active'" type="button" @click="setStatus(pkg, 'retired')">下架</button>
                <button v-if="pkg.status === 'retired'" type="button" @click="setStatus(pkg, 'active')">重新上架</button>
              </template>
            </td>
          </tr>
        </tbody>
      </table>
    </section>

    <section aria-label="新建套餐">
      <h4>新建套餐（创建后为 draft）</h4>
      <div class="create-form">
        <input v-model="form.name" type="text" placeholder="名称（1-50 字）" aria-label="套餐名称">
        <input v-model="form.description" type="text" placeholder="描述（选填）" aria-label="套餐描述">
        <input v-model="form.priceYuan" type="text" placeholder="价格（元）" aria-label="价格">
        <input v-model="form.creditsAmount" type="text" placeholder="积分面值" aria-label="面值">
        <button type="button" :disabled="!form.name.trim() || !form.priceYuan || !form.creditsAmount"
          @click="createPackage">创建</button>
      </div>
    </section>

    <section aria-label="购买订单">
      <h4>购买订单（最近 50 条）</h4>
      <table class="data-table">
        <thead>
          <tr><th>订单</th><th>账号</th><th>价格</th><th>面值</th><th>状态</th></tr>
        </thead>
        <tbody>
          <tr v-if="!orders.length">
            <td colspan="5" class="muted">暂无订单</td>
          </tr>
          <tr v-for="order in orders" :key="order.id">
            <td class="mono">{{ order.id.slice(0, 8) }}</td>
            <td class="mono">{{ order.accountId.slice(0, 8) }}</td>
            <td>¥{{ formatPrice(order.priceCents) }}</td>
            <td>{{ order.creditsAmount }} 积分</td>
            <td>
              <span class="status-tag" :class="{ paid: order.status === 'paid' }">{{ order.status }}</span>
            </td>
          </tr>
        </tbody>
      </table>
    </section>
  </div>
</template>

<style scoped>
.credits-packages-panel { display: grid; gap: 18px; }
.panel-toolbar { display: flex; align-items: center; gap: 12px; }
.refresh-btn { padding: 6px 14px; border-radius: var(--radius-md); border: 1px solid var(--color-border); background: none; cursor: pointer; }
.notice { color: var(--color-success); font-size: 0.88rem; }
.error { color: var(--color-danger); font-size: 0.88rem; }
h4 { margin: 0 0 8px; font-size: 0.94rem; color: var(--color-text); }
.data-table { width: 100%; border-collapse: collapse; font-size: 0.86rem; }
.data-table th, .data-table td { padding: 8px 10px; border-bottom: 1px solid var(--color-border); text-align: left; }
.data-table th { color: var(--color-text-muted); font-weight: 600; }
.muted { color: var(--color-text-muted); }
.mono { font-family: ui-monospace, monospace; }
.status-tag { padding: 2px 8px; border-radius: var(--radius-pill); font-size: 0.76rem; background: var(--surface-muted); color: var(--color-text-muted); }
.status-tag.active, .status-tag.paid { background: color-mix(in srgb, var(--color-success) 12%, transparent); color: var(--color-success); }
.status-tag.retired { background: color-mix(in srgb, var(--color-danger) 10%, transparent); color: var(--color-danger); }
.actions-cell { display: flex; gap: 6px; flex-wrap: wrap; }
.actions-cell input { width: 76px; padding: 4px 8px; border: 1px solid var(--color-border); border-radius: var(--radius-sm); }
.actions-cell button, .create-form button { padding: 4px 10px; border-radius: var(--radius-sm); border: 1px solid var(--color-border); background: none; cursor: pointer; font-size: 0.8rem; }
.create-form { display: flex; gap: 8px; flex-wrap: wrap; }
.create-form input { padding: 7px 10px; border: 1px solid var(--color-border); border-radius: var(--radius-md); }
.create-form button:disabled { opacity: 0.5; cursor: not-allowed; }
</style>
