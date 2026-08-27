<script setup lang="ts">
import { computed, ref } from 'vue'
import { useGrassland } from '../../../composables/useGrassland'
import type { AdminOrganizationSummary } from '../../../types/grassland'

/**
 * 成员账号前缀管理（任务书 #51 第 1 条）。前缀自动生成、商家只读，改名是<b>运营动作</b>：
 * 它会连带重写该主体下全部成员的登录名（旧登录名立即失效，已登录成员不掉线）。
 *
 * 商家侧入口已整体下线，这里是全平台唯一的改名入口。故 UI 的重点是<b>让运营看清影响面</b>：
 * 搜索结果带成员数、确认弹窗写明会改掉几个人的登录名、改完回显实际重写行数。
 */
const grassland = useGrassland()

const searchInput = ref('')
const results = ref<AdminOrganizationSummary[]>([])
const searched = ref(false)
const notice = ref('')

/** 改名目标：选中后才出现改名区（避免在列表里逐行放危险输入框）。 */
const target = ref<AdminOrganizationSummary | null>(null)
const newPrefix = ref('')

/** 前缀规则与后端逐字一致（^[a-z0-9]{3,24}$）：连字符是禁例——账号名靠它分段。 */
const PREFIX_RE = /^[a-z0-9]{3,24}$/
const prefixValid = computed(() => PREFIX_RE.test(newPrefix.value.trim().toLowerCase()))
/** 与当前值相同时后端 400，前端先禁用（省一次无意义往返）。 */
const prefixUnchanged = computed(() =>
  target.value !== null && newPrefix.value.trim().toLowerCase() === target.value.accountPrefix)
const canSubmit = computed(() => prefixValid.value && !prefixUnchanged.value)

/**
 * @param keepNotice 改名成功后的列表刷新要保留成功提示——否则刚写的影响面回显会被这里清掉
 *   （运营就看不到「改了几个人」，而这正是本面板存在的意义）。
 */
async function search(keepNotice = false): Promise<void> {
  if (!keepNotice) notice.value = ''
  const list = await grassland.searchAdminOrganizations(searchInput.value.trim())
  searched.value = true
  if (Array.isArray(list)) results.value = list as AdminOrganizationSummary[]
}

function selectTarget(row: AdminOrganizationSummary): void {
  target.value = row
  newPrefix.value = ''
  notice.value = ''
}

function cancelTarget(): void {
  target.value = null
  newPrefix.value = ''
}

/**
 * 改名执行。强确认写明影响面——这是不可逆的对外可见变更：成员旧登录名立即失效，
 * 且系统不会自动告知新登录名（会话不动，拍板 C），线下通知由运营负责。
 */
async function applyRename(): Promise<void> {
  const org = target.value
  if (!org || !canSubmit.value) return
  const next = newPrefix.value.trim().toLowerCase()
  const affected = org.memberCount
  const warning = affected > 0
    ? `确认把「${org.name}」的账号前缀从 ${org.accountPrefix} 改为 ${next}？\n\n`
      + `该主体下 ${affected} 个成员的登录名会同步改成「${next}-原登录名」，旧登录名立即失效。\n`
      + '已登录的成员不会掉线，但他们不会自动收到新登录名——需要你线下通知。'
    : `确认把「${org.name}」的账号前缀从 ${org.accountPrefix} 改为 ${next}？\n\n`
      + '该主体当前没有成员账号，本次只改前缀本身。'
  if (!window.confirm(warning)) return

  notice.value = ''
  const result = await grassland.setAdminAccountPrefix(org.id, next)
  if (!result) return
  notice.value = `已改为 ${result.prefix}：重写 ${result.rewrittenAccounts} 个成员登录名`
    + `（其中 ${result.rewrittenPlaceholderEmails} 个未绑邮箱的占位邮箱同步更新）`
  cancelTarget()
  await search(true)
}
</script>

<template>
  <article class="prefix-panel">
    <header class="panel-head">
      <h3>成员账号前缀</h3>
      <p class="panel-note">
        前缀由系统自动生成、商家不可自改。改前缀会连带重写该主体下全部成员的登录名，
        旧登录名立即失效（已登录成员不掉线）。
      </p>
    </header>

    <p v-if="grassland.error.value" class="gl-alert gl-alert-error" role="alert">{{ grassland.error.value }}</p>
    <p v-if="notice" class="gl-alert gl-alert-ok" role="status">{{ notice }}</p>

    <!-- 显式无参调用：`@submit.prevent="search"` 会把 event 当作 keepNotice 传进去（truthy） -->
    <form class="panel-toolbar" @submit.prevent="search()">
      <input
        v-model="searchInput"
        aria-label="搜索商家主体（名称 / 前缀 / ID）"
        placeholder="搜索商家主体：名称 / 前缀 / ID"
        maxlength="120"
      >
      <button type="submit" :disabled="grassland.loading.value">搜索</button>
    </form>

    <p v-if="searched && results.length === 0" class="gl-empty">没有匹配的商家主体。</p>
    <div v-else-if="results.length > 0" class="table-card">
      <table class="prefix-table">
        <thead>
          <tr><th>主体</th><th>当前前缀</th><th>成员数</th><th>状态</th><th>操作</th></tr>
        </thead>
        <tbody>
          <tr v-for="row in results" :key="row.id">
            <td>
              {{ row.name }}
              <code class="row-id" :title="row.id">{{ row.id.slice(0, 8) }}…</code>
            </td>
            <td><code>{{ row.accountPrefix }}</code></td>
            <td>{{ row.memberCount }}</td>
            <td>{{ row.status }}</td>
            <td>
              <button type="button" :disabled="grassland.loading.value" @click="selectTarget(row)">改前缀</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- 改名区：选中后出现。刻意与列表分离——危险动作不放在逐行里 -->
    <section v-if="target" class="rename-zone" data-testid="prefix-rename-zone">
      <h4>改「{{ target.name }}」的账号前缀</h4>
      <p class="zone-warn">
        当前 <code>{{ target.accountPrefix }}</code>，该主体下 <strong>{{ target.memberCount }}</strong> 个成员。
        改名后这些成员的登录名变为「新前缀-原登录名」，<strong>旧登录名立即失效</strong>；
        系统不会自动告知新登录名，需你线下通知。
      </p>
      <div class="zone-row">
        <input
          v-model="newPrefix"
          aria-label="新前缀"
          placeholder="新前缀（3-24 位小写字母或数字）"
          maxlength="24"
        >
        <button type="button" class="danger" :disabled="grassland.loading.value || !canSubmit" @click="applyRename">
          改前缀并重写成员登录名
        </button>
        <button type="button" @click="cancelTarget">取消</button>
      </div>
      <p v-if="newPrefix.trim() && !prefixValid" class="zone-hint">
        前缀仅支持 3-24 位小写字母或数字（不含连字符——账号名靠它分段）。
      </p>
      <p v-else-if="prefixUnchanged" class="zone-hint">新前缀与当前前缀相同。</p>
    </section>
  </article>
</template>

<style scoped>
.prefix-panel { display: grid; gap: 10px; }
.panel-head { display: grid; gap: 4px; }
.panel-head h3 { margin: 0; font-size: 15px; }
.panel-note { margin: 0; font-size: 12px; opacity: 0.68; line-height: 1.5; }
.panel-toolbar { display: flex; gap: 8px; align-items: center; }
.panel-toolbar input { flex: 1; min-width: 200px; min-height: 34px; padding: 4px 10px; border: 1px solid var(--color-border); border-radius: var(--radius-sm); background: var(--surface-muted); color: var(--color-text); font-size: 13px; }
.panel-toolbar button { min-height: 34px; padding: 0 14px; border-radius: var(--radius-sm); border: 1px solid var(--color-border); background: transparent; color: var(--color-text); cursor: pointer; font-size: 13px; }
.table-card { overflow-x: auto; }
.prefix-table { width: 100%; border-collapse: collapse; font-size: 13px; }
.prefix-table th, .prefix-table td { text-align: left; padding: 6px 8px; border-bottom: 1px solid var(--color-border); }
.prefix-table button { min-height: 30px; padding: 0 12px; border-radius: var(--radius-sm); border: 1px solid var(--color-border); background: transparent; color: var(--color-text); cursor: pointer; font-size: 12px; }
.row-id { font-size: 11px; opacity: 0.55; margin-left: 6px; }
.rename-zone { display: grid; gap: 8px; padding: 12px; border: 1px solid var(--color-danger); border-radius: var(--radius-md); }
.rename-zone h4 { margin: 0; font-size: 14px; }
.zone-warn { margin: 0; font-size: 12px; line-height: 1.6; }
.zone-row { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.zone-row input { min-width: 240px; min-height: 34px; padding: 4px 10px; border: 1px solid var(--color-border); border-radius: var(--radius-sm); background: var(--surface-muted); color: var(--color-text); font-size: 13px; }
.zone-row button { min-height: 34px; padding: 0 14px; border-radius: var(--radius-sm); border: 1px solid var(--color-border); background: transparent; color: var(--color-text); cursor: pointer; font-size: 13px; }
.zone-row button:disabled { opacity: 0.45; cursor: not-allowed; }
.danger { color: var(--color-danger); border-color: currentColor !important; }
.zone-hint { margin: 0; font-size: 12px; color: var(--color-danger); }
.gl-empty { margin: 0; font-size: 13px; opacity: 0.6; }
</style>
