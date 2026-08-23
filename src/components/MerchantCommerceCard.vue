<template>
  <article class="commerce-card">
    <header class="card-head">
      <div><h3>到店套餐与核销</h3><p>套餐每次保存生成不可变版本；下单后价格、归因与分账不再随编辑变化。</p></div>
      <div class="header-actions">
        <button type="button" :disabled="commerce.loading.value || !organizationId" @click="refresh">刷新</button>
        <button type="button" :disabled="!organizationId" @click="exportOrders('csv')">导出 CSV</button>
        <button type="button" :disabled="!organizationId" @click="exportOrders('xlsx')">导出 Excel</button>
      </div>
    </header>
    <p v-if="commerce.error.value" class="alert error">{{ commerce.error.value }}</p>
    <p v-if="notice" class="alert ok">{{ notice }}</p>

    <section class="form-grid">
      <input v-model="form.title" placeholder="套餐名称" />
      <input v-model.number="form.priceYuan" type="number" min="0.01" step="0.01" placeholder="价格（元）" />
      <input v-model.number="form.totalStock" type="number" min="0" placeholder="总库存" />
      <input v-model.number="form.validDays" type="number" min="1" placeholder="购买后有效天数（可留空）" />
      <label class="date-field"><span>固定核销截止时间（可选）</span><input v-model="form.fixedDeadline" type="datetime-local" /></label>
      <input v-model.number="form.recommenderPct" type="number" min="0" max="100" step="0.1" placeholder="推荐官 %" />
      <input v-model.number="form.platformPct" type="number" min="0" max="100" step="0.1" placeholder="平台 %" />
      <input v-model="form.description" class="wide" placeholder="套餐说明" />
      <div class="wide slots-editor">
        <div class="slots-head">
          <strong>分时段库存（可选）</strong>
          <button type="button" @click="addSlotRow">+ 添加时段</button>
        </div>
        <p class="hint">启用后消费者按所选时段下单，各时段余量独立核算；保存生成不可变新版本。总库存按各时段数量之和写入。</p>
        <div v-for="(row, index) in slotRows" :key="index" class="slot-row">
          <label>开始<input v-model="row.start" type="datetime-local" /></label>
          <label>结束<input v-model="row.end" type="datetime-local" /></label>
          <label>数量<input v-model="row.stock" inputmode="numeric" placeholder="如 5" /></label>
          <button type="button" class="danger" @click="slotRows.splice(index, 1)">删除</button>
        </div>
      </div>
      <div class="wide actions">
        <button type="button" :disabled="!canSave || commerce.loading.value" @click="save">
          {{ editingId ? '保存为新版本' : '创建套餐草稿' }}
        </button>
        <button v-if="editingId" type="button" @click="resetForm">取消编辑</button>
      </div>
    </section>

    <section class="package-list">
      <p v-if="packages.length === 0" class="empty">当前资源范围暂无套餐。</p>
      <div v-for="item in packages" :key="item.id" class="package-row">
        <div>
          <strong>{{ item.title }}</strong><span>{{ statusLabel(item.status) }}</span>
          <p>¥{{ yuan(item.priceCents) }} · 库存 {{ item.remainingStock }}/{{ item.totalStock }} · v{{ item.version }}</p>
          <div v-if="item.inventorySlots?.length" class="slot-summary">
            <span v-for="slot in item.inventorySlots" :key="slot.id" :class="{ tight: slot.remainingStock <= 0 }">
              {{ slotBrief(slot) }} · 余 {{ slot.remainingStock }}
            </span>
          </div>
          <code>{{ item.id }}</code>
        </div>
        <div class="row-actions">
          <button type="button" @click="edit(item)" title="历史版本不可变；已下单消费者按下单时的版本结算">编辑（存为新版本）</button>
          <button v-if="item.status !== 'published'" type="button" @click="publish(item.id)">上架</button>
          <button v-else type="button" @click="offSale(item.id)">下架</button>
          <button type="button" @click="selectPromotion(item)">推广链接/二维码</button>
        </div>
      </div>
    </section>

    <section v-if="promotionPackage" class="promotion-box">
      <div>
        <h4>{{ promotionPackage.title }} · 推荐官推广</h4>
        <input v-model="recommenderAccountId" placeholder="推荐官账号 ID（留空为自然流量）" @input="renderPromotionQr" />
        <div class="copy-row"><input :value="promotionUrl" readonly /><button type="button" @click="copyPromotion">复制链接</button></div>
      </div>
      <img v-if="promotionQr" :src="promotionQr" alt="套餐推广二维码" />
    </section>

    <section class="redemption-grid">
      <div>
        <h4>商家扫码/输入核销</h4>
        <div class="copy-row"><input v-model="redeemCode" placeholder="GL-XXXXX-XXXXX-XXXXX-XXXXX" @keyup.enter="redeem" /><button type="button" :disabled="!redeemCode.trim()" @click="redeem">核销</button></div>
        <div class="scanner-actions">
          <button v-if="scannerSupported" type="button" :disabled="scanning || commerce.loading.value" @click="startScanner">打开摄像头扫码</button>
          <button v-if="scanning" type="button" @click="stopScanner">关闭摄像头</button>
          <small v-else-if="!scannerSupported">当前浏览器不支持原生二维码识别，请使用扫码枪或手工输码。</small>
        </div>
        <div v-if="scanning" class="scanner-box">
          <video ref="scannerVideo" autoplay muted playsinline aria-label="核销二维码扫描画面"></video>
          <p>请将消费者核销二维码置于取景框内。</p>
        </div>
        <p v-if="scannerNotice" class="scanner-notice">{{ scannerNotice }}</p>
      </div>
      <div>
        <h4>订单监控</h4>
        <p v-if="orders.length === 0" class="empty">暂无订单。</p>
        <div v-else class="compact-orders">
          <section v-for="order in orders" :key="order.id" class="compact-order" :class="{ disputed: order.status === 'after_sales_disputed' }">
            <p>
              <strong>{{ order.packageTitle }}</strong> · {{ orderStatus(order.status) }} · ¥{{ yuan(order.priceCents) }}
              <template v-if="(order.refundedAmountCents ?? 0) > 0">（已退 ¥{{ yuan(order.refundedAmountCents ?? 0) }}）</template>
              <template v-if="order.slotStart"> · {{ formatSlot(order.slotStart, order.slotEnd ?? order.slotStart) }}</template>
            </p>
            <div v-if="order.status === 'after_sales_disputed'" class="dispute-handle">
              <button type="button" class="warn" @click="toggleResolve(order.id)">
                {{ resolving[order.id] ? '收起裁定' : '处理售后' }}
              </button>
            </div>
            <div v-if="resolving[order.id] && order.status === 'after_sales_disputed'" class="resolve-form">
              <p v-if="disputeDetails[order.id]">消费者申诉：{{ disputeDetails[order.id].reason }}</p>
              <div class="resolve-row">
                <input v-model="resolveDrafts[order.id]!.amountYuan" inputmode="decimal" class="amount"
                  :placeholder="`退款金额（元，≤ ${yuan(order.priceCents - (order.refundedAmountCents ?? 0))}，空=全退）`" />
                <button type="button" :disabled="commerce.loading.value" @click="resolveDispute(order, 'refund')">裁定退款</button>
                <button type="button" class="danger" :disabled="commerce.loading.value" @click="resolveDispute(order, 'reject')">驳回</button>
              </div>
              <input v-model="resolveDrafts[order.id]!.reason" placeholder="裁定说明（可选，写入争议记录）" />
            </div>
          </section>
        </div>
      </div>
    </section>
  </article>
</template>

<script setup lang="ts">
import QRCode from 'qrcode'
import { computed, nextTick, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { useCommerce } from '../composables/useCommerce'
import type { CommercePackage, ConsumerOrder, InventorySlot } from '../types/commerce'

const props = defineProps<{ organizationId: string; storeId?: string }>()
const commerce = useCommerce()
const packages = ref<CommercePackage[]>([])
const orders = ref<ConsumerOrder[]>([])
const editingId = ref('')
const notice = ref('')
const redeemCode = ref('')
const promotionPackage = ref<CommercePackage | null>(null)
const recommenderAccountId = ref('')
const promotionQr = ref('')
const scanning = ref(false)
const scannerVideo = ref<HTMLVideoElement | null>(null)
const scannerNotice = ref('')
const slotRows = reactive<Array<{ start: string; end: string; stock: string }>>([])
const resolving = reactive<Record<string, boolean>>({})
const resolveDrafts = reactive<Record<string, { amountYuan: string; reason: string }>>({})
const disputeDetails = reactive<Record<string, import('../types/commerce').AfterSalesDispute>>({})
let scannerStream: MediaStream | null = null
let scannerFrame = 0
let detector: { detect(source: HTMLVideoElement): Promise<Array<{ rawValue?: string }>> } | null = null

interface PackageForm {
  title: string
  description: string
  priceYuan: number
  totalStock: number
  validDays: number | ''
  fixedDeadline: string
  recommenderPct: number
  platformPct: number
}
const form = reactive<PackageForm>({ title: '', description: '', priceYuan: 99, totalStock: 100, validDays: 30, fixedDeadline: '', recommenderPct: 10, platformPct: 5 })

const canSave = computed(() => {
  if (!(props.organizationId && form.title.trim() && form.priceYuan > 0)) return false
  if (form.recommenderPct + form.platformPct > 100) return false
  if (slotRows.length > 0) return !slotRows.some(invalidSlotRow)
  return form.totalStock >= 0
    && ((typeof form.validDays === 'number' && form.validDays > 0) || form.fixedDeadline)
})

function addSlotRow(): void {
  slotRows.push({ start: '', end: '', stock: '' })
}

function invalidSlotRow(row: { start: string; end: string; stock: string }): boolean {
  const stock = Number.parseInt(row.stock, 10)
  return !row.start || !row.end
    || !(Number.isFinite(stock) && stock >= 1)
    || new Date(row.end).getTime() <= new Date(row.start).getTime()
}

function slotInputs(): Array<{ storeId?: string; slotStart: string; slotEnd: string; totalStock: number }> | undefined {
  if (slotRows.length === 0) return undefined
  return slotRows.map(row => ({
    ...(props.storeId ? { storeId: props.storeId } : {}),
    slotStart: new Date(row.start).toISOString(),
    slotEnd: new Date(row.end).toISOString(),
    totalStock: Number.parseInt(row.stock, 10),
  }))
}
const scannerSupported = computed(() => Boolean(
  navigator.mediaDevices && barcodeDetectorConstructor()))
const promotionUrl = computed(() => {
  if (!promotionPackage.value) return ''
  const url = new URL(window.location.origin + window.location.pathname)
  url.searchParams.set('view', 'commerce')
  url.searchParams.set('package', promotionPackage.value.id)
  if (recommenderAccountId.value.trim()) url.searchParams.set('recommender', recommenderAccountId.value.trim())
  return url.toString()
})

watch(() => [props.organizationId, props.storeId], () => { void refresh() }, { immediate: true })

async function refresh(): Promise<void> {
  if (!props.organizationId) { packages.value = []; orders.value = []; return }
  const [packageValues, orderValues] = await Promise.all([
    commerce.listMerchantPackages(props.organizationId, props.storeId),
    commerce.listMerchantOrders(props.organizationId, props.storeId),
  ])
  if (packageValues) packages.value = packageValues
  if (orderValues) orders.value = orderValues
  for (const order of Array.isArray(orderValues) ? orderValues : []) {
    resolveDrafts[order.id] ||= { amountYuan: '', reason: '' }
    if (order.status === 'after_sales_disputed' && !disputeDetails[order.id]) {
      const detail = await commerce.getAfterSalesDispute(order.id)
      if (detail) disputeDetails[order.id] = detail
    }
  }
}

function exportOrders(format: 'csv' | 'xlsx'): void {
  if (!props.organizationId) return
  const query = new URLSearchParams({ organizationId: props.organizationId, format })
  if (props.storeId) query.set('storeId', props.storeId)
  window.location.assign(`/api/v2/merchant/orders/export?${query}`)
}

async function save(): Promise<void> {
  const slots = slotInputs()
  const totalStock = slots ? slots.reduce((sum, slot) => sum + slot.totalStock, 0) : form.totalStock
  const input = {
    organizationId: props.organizationId,
    ...(props.storeId ? { storeId: props.storeId } : {}),
    title: form.title.trim(), description: form.description.trim(),
    priceCents: Math.round(form.priceYuan * 100), totalStock,
    ...(typeof form.validDays === 'number' && form.validDays > 0
      ? { validDaysAfterPurchase: form.validDays } : {}),
    ...(form.fixedDeadline
      ? { fixedRedeemDeadline: new Date(form.fixedDeadline).toISOString() } : {}),
    recommenderShareBps: Math.round(form.recommenderPct * 100),
    platformFeeBps: Math.round(form.platformPct * 100), policyVersion: 'commerce-v1',
    ...(slots ? { inventorySlots: slots } : {}),
  }
  const result = editingId.value
    ? await commerce.revisePackage(editingId.value, input)
    : await commerce.createPackage(input)
  if (!result) return
  notice.value = editingId.value ? `已生成不可变版本 v${result.version}` : '套餐草稿已创建'
  resetForm()
  await refresh()
}

function edit(item: CommercePackage): void {
  editingId.value = item.id
  Object.assign(form, {
    title: item.title, description: item.description, priceYuan: item.priceCents / 100,
    totalStock: item.totalStock, validDays: item.validDaysAfterPurchase || '',
    fixedDeadline: item.fixedRedeemDeadline ? localDateTime(item.fixedRedeemDeadline) : '',
    recommenderPct: item.recommenderShareBps / 100, platformPct: item.platformFeeBps / 100,
  })
  slotRows.length = 0
  for (const slot of item.inventorySlots ?? []) {
    slotRows.push({
      start: localDateTime(slot.slotStart),
      end: localDateTime(slot.slotEnd),
      stock: String(slot.totalStock),
    })
  }
}
function resetForm(): void {
  editingId.value = ''
  Object.assign(form, { title: '', description: '', priceYuan: 99, totalStock: 100, validDays: 30, fixedDeadline: '', recommenderPct: 10, platformPct: 5 })
  slotRows.length = 0
}
async function publish(id: string): Promise<void> { if (await commerce.publishPackage(id)) { notice.value = '套餐已上架'; await refresh() } }
async function offSale(id: string): Promise<void> { if (await commerce.offSalePackage(id)) { notice.value = '套餐已下架'; await refresh() } }
async function selectPromotion(item: CommercePackage): Promise<void> { promotionPackage.value = item; await renderPromotionQr() }
async function renderPromotionQr(): Promise<void> { promotionQr.value = promotionUrl.value ? await QRCode.toDataURL(promotionUrl.value, { width: 220, margin: 1 }) : '' }
async function copyPromotion(): Promise<void> { await navigator.clipboard.writeText(promotionUrl.value); notice.value = '推广链接已复制' }
async function redeem(): Promise<void> {
  const result = await commerce.redeem(redeemCode.value.trim())
  if (!result) return
  notice.value = result.status === 'redeemed' ? '核销成功，三方分账已完成' : '核销已受理，分账正在重试'
  redeemCode.value = ''
  await refresh()
}

function toggleResolve(orderId: string): void {
  resolving[orderId] = !resolving[orderId]
}

async function resolveDispute(order: ConsumerOrder, resolution: 'refund' | 'reject'): Promise<void> {
  const draft = resolveDrafts[order.id]!
  const trimmed = draft.amountYuan.trim()
  let amountCents: number | undefined
  if (resolution === 'refund' && trimmed !== '') {
    amountCents = Math.round(Number.parseFloat(trimmed) * 100)
    const remaining = order.priceCents - (order.refundedAmountCents ?? 0)
    if (!Number.isFinite(amountCents) || amountCents <= 0 || amountCents > remaining) {
      commerce.error.value = `退款金额不合法（可退 ¥${yuan(remaining)}）`
      return
    }
  }
  const updated = await commerce.resolveAfterSalesDispute(
    order.id, resolution, amountCents, draft.reason.trim() || undefined)
  if (!updated) return
  resolving[order.id] = false
  notice.value = resolution === 'refund'
    ? `已裁定退款，订单状态：${orderStatus(updated.status)}`
    : '已驳回售后争议。'
  await refresh()
}
async function startScanner(): Promise<void> {
  const Detector = barcodeDetectorConstructor()
  if (!Detector || !navigator.mediaDevices) return
  scannerNotice.value = ''
  try {
    scannerStream = await navigator.mediaDevices.getUserMedia({
      audio: false,
      video: { facingMode: { ideal: 'environment' } },
    })
    detector = new Detector({ formats: ['qr_code'] })
    scanning.value = true
    await nextTick()
    if (!scannerVideo.value) throw new Error('扫描画面初始化失败')
    scannerVideo.value.srcObject = scannerStream
    await scannerVideo.value.play()
    scanFrame()
  } catch (error: unknown) {
    stopScanner()
    scannerNotice.value = error instanceof Error ? error.message : '无法打开摄像头'
  }
}
function scanFrame(): void {
  scannerFrame = window.requestAnimationFrame(async () => {
    if (!scanning.value || !scannerVideo.value || !detector) return
    try {
      if (scannerVideo.value.readyState >= 2) {
        const results = await detector.detect(scannerVideo.value)
        const value = results.find(result => result.rawValue?.trim())?.rawValue?.trim()
        if (value) {
          redeemCode.value = value
          stopScanner()
          await redeem()
          return
        }
      }
    } catch {
      // Some implementations reject frames while the camera is warming up; retry the next frame.
    }
    if (scanning.value) scanFrame()
  })
}
function stopScanner(): void {
  scanning.value = false
  if (scannerFrame) window.cancelAnimationFrame(scannerFrame)
  scannerFrame = 0
  scannerStream?.getTracks().forEach(track => track.stop())
  scannerStream = null
  detector = null
  if (scannerVideo.value) scannerVideo.value.srcObject = null
}
function barcodeDetectorConstructor(): (new (options?: { formats?: string[] }) => {
  detect(source: HTMLVideoElement): Promise<Array<{ rawValue?: string }>>
}) | undefined {
  return (globalThis as typeof globalThis & { BarcodeDetector?: new (options?: { formats?: string[] }) => {
    detect(source: HTMLVideoElement): Promise<Array<{ rawValue?: string }>>
  } }).BarcodeDetector
}
function localDateTime(value: string): string {
  const date = new Date(value)
  const offset = date.getTimezoneOffset() * 60_000
  return new Date(date.getTime() - offset).toISOString().slice(0, 16)
}
function slotBrief(slot: InventorySlot): string {
  return formatSlot(slot.slotStart, slot.slotEnd)
}
function formatSlot(start: string, end: string): string {
  const from = new Date(start)
  const to = new Date(end)
  const sameDay = from.toDateString() === to.toDateString()
  return `${from.toLocaleString()} ~ ${sameDay ? to.toLocaleTimeString() : to.toLocaleString()}`
}
function yuan(cents: number): string { return (cents / 100).toFixed(2) }
function statusLabel(status: CommercePackage['status']): string { return ({ draft: '草稿', published: '已上架', off_sale: '已下架' })[status] }
function orderStatus(status: ConsumerOrder['status']): string { return ({ pending_payment: '支付处理中', paid: '待核销', redeeming: '分账中', redeemed: '已核销', refund_pending: '退款中', partially_refunded: '部分退款', refunded: '已退款', after_sales_disputed: '售后争议', payment_failed: '支付失败', cancelled: '已取消' })[status] }
onBeforeUnmount(stopScanner)
</script>

<style scoped>
.commerce-card { grid-column: 1 / -1; display: grid; gap: 14px; padding: 16px; border: 1px solid var(--color-border); border-radius: 10px; }
.card-head, .package-row, .promotion-box, .copy-row, .actions, .row-actions { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.header-actions { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.card-head h3, .card-head p, .package-row p, .promotion-box h4, .redemption-grid h4 { margin: 0; }.card-head p, .package-row p, .empty { font-size: 12px; opacity: .7; }
.form-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; }.wide { grid-column: 1 / -1; }.date-field { display: grid; gap: 3px; font-size: 11px; opacity: .8; }
.slots-editor { display: grid; gap: 8px; padding: 10px; border: 1px dashed var(--color-border); border-radius: 9px; }
.slots-head { display: flex; align-items: center; justify-content: space-between; }
.slots-editor .hint { margin: 0; font-size: 11px; opacity: .68; }
.slot-row { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.slot-row label { display: grid; gap: 2px; font-size: 11px; opacity: .75; }
.slot-row .danger { color: var(--color-danger); }
.slot-summary { display: flex; flex-wrap: wrap; gap: 6px; margin: 6px 0 0; }
.slot-summary span { padding: 2px 8px; border-radius: 999px; font-size: 11px; background: color-mix(in srgb, var(--color-accent) 10%, transparent); }
.slot-summary span.tight { color: var(--color-danger); }
.compact-orders { max-height: 320px; overflow: auto; display: grid; gap: 8px; }
.compact-order { padding: 8px 10px; border: 1px solid var(--color-border); border-radius: 9px; }
.compact-order p { margin: 0; font-size: 12px; }
.compact-order.disputed { border-color: var(--color-warning); background: color-mix(in srgb, var(--color-warning) 7%, transparent); }
.dispute-handle { margin-top: 6px; }
.resolve-form { display: grid; gap: 8px; margin-top: 8px; padding-top: 8px; border-top: 1px dashed var(--color-border); }
.resolve-form > p { margin: 0; font-size: 12px; opacity: .8; }
.resolve-row { display: flex; gap: 6px; flex-wrap: wrap; }
.resolve-row input.amount { flex: 1; min-width: 160px; }
.resolve-row button, button.warn { border-color: var(--color-warning); color: var(--color-warning); }
input, button { min-height: 36px; padding: 7px 9px; border: 1px solid var(--color-border); border-radius: 7px; background: var(--color-surface); color: var(--color-text); } button { cursor: pointer; }
.package-list { display: grid; gap: 8px; }.package-row { padding: 10px; border: 1px solid var(--color-border); border-radius: 9px; }.package-row span { margin-left: 8px; font-size: 11px; opacity: .7; }.package-row code { font-size: 10px; opacity: .6; }
.row-actions { justify-content: flex-end; flex-wrap: wrap; }.promotion-box { align-items: stretch; padding: 14px; border-radius: 10px; background: color-mix(in srgb, var(--color-accent) 8%, transparent); }.promotion-box > div { flex: 1; display: grid; gap: 8px; }.promotion-box img { width: 150px; height: 150px; }
.copy-row input { flex: 1; }.redemption-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }.redemption-grid > div { padding: 12px; border: 1px solid var(--color-border); border-radius: 9px; }.scanner-actions { display: flex; align-items: center; gap: 8px; margin-top: 8px; }.scanner-actions small { opacity: .68; }.scanner-box { position: relative; margin-top: 8px; overflow: hidden; border-radius: 10px; background: #111; }.scanner-box video { display: block; width: 100%; max-height: 260px; object-fit: cover; }.scanner-box p { position: absolute; inset: auto 8px 8px; margin: 0; padding: 5px 8px; border-radius: 6px; color: white; background: rgba(0, 0, 0, .65); }.scanner-notice { margin: 7px 0 0; color: var(--color-danger); font-size: 12px; }
.alert { margin: 0; padding: 8px 10px; border-radius: 8px; }.alert.error { color: var(--color-danger); }.alert.ok { color: var(--color-success); }
@media (max-width: 760px) { .form-grid, .redemption-grid { grid-template-columns: 1fr; }.promotion-box, .package-row, .card-head { align-items: stretch; flex-direction: column; }.promotion-box img { align-self: center; } }
</style>
