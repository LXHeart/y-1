<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useGrassland } from '../../../composables/useGrassland'
import type { OrganizationRenameRequest } from '../../../types/grassland'
import OpsPagination from './OpsPagination.vue'

/**
 * 商家主体更名审核（V40 / 2026-08-23 产品规则）：更名须平台审核通过才生效。
 * approve = 生效（同事务改名）；reject = 驳回留痕（驳回不占 30 天冷却）。
 */
const pageSize = ref(10)
const grassland = useGrassland()
const requests = ref<OrganizationRenameRequest[]>([])
const loading = ref(false)
const notice = ref('')
const notes = ref<Record<string, string>>({})
const offset = ref(0)
const total = ref(0)

async function refresh(): Promise<void> {
  loading.value = true
  const list = await grassland.listAdminOrgRenames({ limit: pageSize.value, offset: offset.value })
  loading.value = false
  if (!list) return
  requests.value = list.items as OrganizationRenameRequest[]
  total.value = list.total
}

/** 翻页：父组件持 offset 真源（任务 #3 分页契约）。 */
function changePage(next: number): void {
  offset.value = next
  void refresh()
}

function changeLimit(limit: number): void {
  pageSize.value = limit
  offset.value = 0
  void refresh()
}

async function review(id: string, decision: 'approve' | 'reject'): Promise<void> {
  const target = requests.value.find((r) => r.id === id)
  if (!target) return
  if (decision === 'approve'
    && !window.confirm(`确认通过更名？「${target.currentName}」→「${target.requestedName}」将立即生效。`)) return
  if (decision === 'reject' && !window.confirm('确认驳回该更名申请？（驳回不占用 30 天冷却，商家可重新申请）')) return
  notice.value = ''
  const ok = await grassland.reviewAdminOrgRename(id, decision, notes.value[id]?.trim() || undefined)
  if (ok === null) return
  notice.value = decision === 'approve' ? '已通过，更名生效' : '已驳回'
  await refresh()
}

function time(iso: string | null): string {
  if (!iso) return ''
  return new Date(iso).toLocaleString('zh-CN', { hour12: false })
}

onMounted(() => { void refresh() })
</script>

<template>
  <article class="org-rename-panel">
    <header class="panel-head">
      <h3>主体更名审核</h3>
      <button type="button" :disabled="loading" @click="refresh">刷新</button>
    </header>
    <p v-if="grassland.error.value" class="gl-alert gl-alert-error" role="alert">{{ grassland.error.value }}</p>
    <p v-if="notice" class="gl-alert gl-alert-ok" role="status">{{ notice }}</p>

    <p v-if="!loading && requests.length === 0" class="gl-empty">暂无待审核的主体更名申请。</p>
    <div v-if="requests.length > 0" class="rename-scroll">
      <ul class="rename-list">
      <li v-for="r in requests" :key="r.id" class="rename-item">
        <div class="rename-main">
          <strong class="rename-names">「{{ r.currentName }}」→「{{ r.requestedName }}」</strong>
          <span class="rename-meta">申请于 {{ time(r.requestedAt) }}</span>
        </div>
        <div class="rename-actions">
          <input
            v-model="notes[r.id]"
            aria-label="审核意见（选填）"
            placeholder="审核意见（选填）"
            maxlength="200"
          >
          <button type="button" class="approve" :disabled="loading" @click="review(r.id, 'approve')">通过并生效</button>
          <button type="button" class="reject" :disabled="loading" @click="review(r.id, 'reject')">驳回</button>
        </div>
      </li>
      </ul>
    </div>
    <OpsPagination v-if="total > 0" :total="total" :limit="pageSize" :offset="offset"
      @change="changePage" @change-limit="changeLimit" />
  </article>
</template>

<style scoped>
.org-rename-panel { display: grid; gap: 10px; }
.panel-head { display: flex; justify-content: space-between; align-items: center; }
.panel-head h3 { margin: 0; font-size: 15px; }
.rename-scroll { max-height: min(520px, 64vh); overflow: auto; }
.rename-list { list-style: none; margin: 0; padding: 0; display: grid; gap: 8px; }
.rename-item { display: grid; gap: 8px; padding: 10px 12px; border: 1px solid var(--color-border); border-radius: var(--radius-md); }
.rename-main { display: grid; gap: 2px; }
.rename-names { font-size: 14px; }
.rename-meta { font-size: 12px; opacity: 0.6; }
.rename-actions { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; }
.rename-actions input { flex: 1; min-width: 180px; min-height: 34px; padding: 4px 10px; border: 1px solid var(--color-border); border-radius: var(--radius-sm); background: var(--surface-muted); color: var(--color-text); font-size: 13px; }
.rename-actions button { min-height: 34px; padding: 0 14px; border-radius: var(--radius-sm); border: 1px solid var(--color-border); background: transparent; cursor: pointer; font-size: 13px; }
.approve { color: var(--color-success); border-color: currentColor; }
.reject { color: var(--color-danger); border-color: currentColor; }
</style>
