<script setup lang="ts">
import { inject, onActivated, onUnmounted, ref } from 'vue'
import OpsPagination from '../components/OpsPagination.vue'
import { ADMIN_BADGE_BRIDGE } from '../adminTabs'
import { useGrassland } from '../../../composables/useGrassland'
import type { RecommenderVerificationRequest } from '../../../types/grassland'
import { formatDateTime } from '../admin-format'

defineOptions({ name: 'AdminRecommendersPanel' })

/**
 * 推荐官认证面板（任务书 #91 A4 自 AdminView.vue 内联分支整段迁出，纯搬运）。
 * fetch=onActivated（D-04）：首次挂载即拉（B-2），之后每次激活重拉（等价原 onActivate）。
 * 徽标（recommenderTotal）经 ADMIN_BADGE_BRIDGE 注册回 AdminView 导航。
 */
const grassland = useGrassland()
const badgeBridge = inject(ADMIN_BADGE_BRIDGE)

const recommenderRequests = ref<RecommenderVerificationRequest[]>([])
const recommenderLoading = ref(false)
const recommenderError = ref('')
const recommenderNotes = ref<Record<string, string>>({})
const recommenderLimit = ref(10)
const recommenderOffset = ref(0)
const recommenderTotal = ref(0)
const reviewingRequestId = ref<string | null>(null)
let listRequestVersion = 0

async function loadRecommenderRequests(): Promise<void> {
  const version = ++listRequestVersion
  recommenderLoading.value = true
  recommenderError.value = ''
  const result = await grassland.listRecommenderVerifications({ limit: recommenderLimit.value, offset: recommenderOffset.value })
  if (version !== listRequestVersion) return
  if (result) {
    recommenderRequests.value = [...result.items]
    recommenderTotal.value = result.total
  } else {
    recommenderError.value = grassland.error.value || '推荐官认证队列加载失败'
  }
  recommenderLoading.value = false
}

function changeRecommenderPage(offset: number): void {
  recommenderOffset.value = offset
  void loadRecommenderRequests()
}

function changeRecommenderLimit(limit: number): void {
  recommenderLimit.value = limit
  recommenderOffset.value = 0
  void loadRecommenderRequests()
}

async function reviewRecommender(request: RecommenderVerificationRequest, decision: 'approve' | 'reject'): Promise<void> {
  if (reviewingRequestId.value) return
  const note = (recommenderNotes.value[request.id] || '').trim()
  if (decision === 'reject' && !note) {
    recommenderError.value = '拒绝推荐官认证必须填写原因'
    return
  }
  recommenderError.value = ''
  reviewingRequestId.value = request.id
  try {
    const result = await grassland.reviewRecommenderVerification(request.id, decision, note || undefined)
    if (result) {
      await loadRecommenderRequests()
      delete recommenderNotes.value[request.id]
    } else {
      recommenderError.value = grassland.error.value || '审核失败'
    }
  } finally {
    reviewingRequestId.value = null
  }
}

badgeBridge?.register('recommenders', () => recommenderTotal.value)
onUnmounted(() => badgeBridge?.unregister('recommenders'))

onActivated(() => {
  void loadRecommenderRequests()
})
</script>

<template>
  <div class="panel-toolbar">
    <div><h3>推荐官平台认证</h3><p>自助开通不受影响，认证通过后获得平台认证标识</p></div>
    <button class="refresh-btn" type="button" :disabled="recommenderLoading" @click="loadRecommenderRequests">刷新</button>
  </div>
  <p v-if="recommenderError" class="error-msg" role="alert">{{ recommenderError }}</p>
  <div v-if="recommenderLoading" class="loading-state">加载中...</div>
  <template v-else>
  <div class="table-card">
    <div class="table-scroll">
    <table class="user-table kyb-table">
      <thead><tr><th>账号</th><th>材料</th><th>提交时间</th><th>审核时限</th><th>审核原因</th><th>操作</th></tr></thead>
      <tbody>
        <tr v-for="item in recommenderRequests" :key="item.id">
          <td class="id-cell" :title="item.accountId">{{ item.accountId }}</td>
          <td class="materials-cell"><code>{{ item.materials || '—' }}</code></td>
          <td class="td-time">{{ formatDateTime(item.createdAt || null) }}</td>
          <td class="td-time">{{ formatDateTime(item.reviewDeadline || null) }}</td>
          <td>
            <input v-model="recommenderNotes[item.id]" class="field-input" type="text" maxlength="500" placeholder="拒绝原因（拒绝必填）" />
          </td>
          <td class="review-actions">
            <button class="approve-btn" type="button" :disabled="reviewingRequestId !== null" @click="reviewRecommender(item, 'approve')">{{ reviewingRequestId === item.id ? '提交中...' : '通过' }}</button>
            <button class="reject-btn" type="button" :disabled="reviewingRequestId !== null" @click="reviewRecommender(item, 'reject')">拒绝</button>
          </td>
        </tr>
        <tr v-if="recommenderRequests.length === 0"><td colspan="6" class="td-empty">暂无待审核认证</td></tr>
      </tbody>
    </table>
    </div>
  </div>
  <OpsPagination :total="recommenderTotal" :limit="recommenderLimit" :offset="recommenderOffset"
    @change="changeRecommenderPage" @change-limit="changeRecommenderLimit" />
  </template>
</template>

<style scoped src="../admin-shared.css"></style>
