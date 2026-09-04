<template>
  <Teleport to="body">
    <div v-if="user" class="drawer-mask" data-testid="user-detail-mask" @click.self="emit('close')">
      <aside class="user-drawer" role="dialog" aria-label="账号详情" data-testid="user-detail-drawer">
        <header class="drawer-head">
          <h3>账号详情</h3>
          <button type="button" class="drawer-close" @click="emit('close')">关闭</button>
        </header>

        <!-- ① 基本 -->
        <section class="drawer-section" data-testid="detail-section-basic" aria-label="基本信息">
          <h4>基本</h4>
          <dl class="detail-kv">
            <div><dt>邮箱</dt><dd class="dd-email">{{ user.email }}</dd></div>
            <div><dt>昵称</dt><dd>{{ user.displayName || '—' }}</dd></div>
            <div><dt>账号 ID</dt><dd><code class="dd-id">{{ user.id }}</code></dd></div>
            <div><dt>注册时间</dt><dd>{{ formatDateTime(user.createdAt) }}</dd></div>
            <div><dt>状态</dt><dd><span class="badge" :class="statusMeta.badge">{{ statusMeta.label }}</span></dd></div>
          </dl>
        </section>

        <!-- ② 身份档案（D5 只读红线：只展示，无任何变更控件） -->
        <section class="drawer-section" data-testid="detail-section-identities" aria-label="身份档案">
          <h4>身份档案</h4>
          <p class="section-note">身份档案为只读展示，不可在此变更（商家身份唯一来源是平台初始化）。</p>
          <ul class="identity-list">
            <li v-if="user.identities?.recommender"><span class="type-tag">推荐官</span>已开通</li>
            <li v-if="user.identities?.merchant">
              <span class="type-tag">商家</span>
              {{ user.identities.ownedOrgNames || '未建主体' }}
              <span
                v-for="org in suspendedOwnedOrgs" :key="org.id"
                class="badge badge-danger"
              >组织已冻结</span>
            </li>
            <li v-if="user.identities?.member"><span class="type-tag">成员</span>属于商家主体成员池</li>
            <li v-if="!hasAnyIdentity" class="td-muted">未开通任何身份</li>
          </ul>
        </section>

        <!-- ③ 所属组织（admin 可冻结/恢复，二次确认） -->
        <section class="drawer-section" data-testid="detail-section-organizations" aria-label="所属组织">
          <h4>所属组织</h4>
          <p v-if="ownedOrgs.length === 0" class="td-muted">未建主体</p>
          <ul v-else class="org-list">
            <li v-for="org in ownedOrgs" :key="org.id" :class="{ 'org-suspended': org.status === 'suspended' }">
              <span class="org-name">{{ org.name }}</span>
              <span class="badge" :class="org.status === 'suspended' ? 'badge-danger' : 'badge-success'">
                {{ org.status === 'suspended' ? '已冻结' : '正常' }}</span>
              <template v-if="admin">
                <button v-if="org.status !== 'suspended'" class="org-btn org-freeze-btn" type="button"
                  @click="pendingOrgAction = { org, action: 'suspend' }">冻结</button>
                <button v-else class="org-btn org-restore-btn" type="button"
                  @click="pendingOrgAction = { org, action: 'restore' }">恢复</button>
              </template>
            </li>
          </ul>
          <div v-if="pendingOrgAction" class="confirm-row" data-testid="org-action-confirm">
            <span>{{
              pendingOrgAction.action === 'suspend'
                ? `冻结后该商家主体下所有成员侧不可见，确认冻结「${pendingOrgAction.org.name}」？`
                : `确认恢复组织「${pendingOrgAction.org.name}」？`
            }}</span>
            <button class="btn-confirm danger" type="button" :disabled="orgSubmitting" @click="executeOrgAction">
              {{ orgSubmitting ? '提交中...' : '确认' }}</button>
            <button class="btn-cancel" type="button" :disabled="orgSubmitting" @click="pendingOrgAction = null">取消</button>
          </div>
          <p v-if="orgError" class="error-msg" role="alert">{{ orgError }}</p>
        </section>

        <!-- ④ 后台角色（admin-only） -->
        <section v-if="admin" class="drawer-section" data-testid="detail-section-roles" aria-label="后台角色">
          <h4>后台角色</h4>
          <div class="role-chips">
            <span v-for="role in user.roles || []" :key="role" class="type-tag">{{ role }}</span>
            <span v-if="!(user.roles && user.roles.length)" class="td-muted">—</span>
          </div>
          <div class="role-editor">
            <select v-model="selectedRole" class="role-select" data-testid="role-select" aria-label="选择后台角色">
              <option v-for="option in BACKEND_ROLE_OPTIONS" :key="option" :value="option">{{ option }}</option>
            </select>
            <button class="grant-btn" type="button" :disabled="roleSubmitting" @click="grantOrRevoke('grant')">授予</button>
            <button class="revoke-btn" type="button" :disabled="roleSubmitting" @click="grantOrRevoke('revoke')">回收</button>
          </div>
          <div v-if="grantAdminConfirm" class="confirm-row" data-testid="grant-admin-confirm">
            <span>授予 platform_admin 将赋予该账号全部平台管理权限（含财务与账号管控），确认继续？</span>
            <button class="btn-confirm danger" type="button" :disabled="roleSubmitting" @click="confirmGrantAdmin">
              {{ roleSubmitting ? '提交中...' : '确认授予' }}</button>
            <button class="btn-cancel" type="button" :disabled="roleSubmitting" @click="grantAdminConfirm = false">取消</button>
          </div>
          <p v-if="roleError" class="error-msg" role="alert">{{ roleError }}</p>
        </section>

        <!-- ⑤ 积分摘要 -->
        <section class="drawer-section" data-testid="detail-section-credits" aria-label="积分摘要">
          <h4>积分摘要</h4>
          <dl class="detail-kv detail-kv-3">
            <div><dt>积分余额</dt><dd class="dd-balance">{{ user.balance }}</dd></div>
            <div><dt>累计获得</dt><dd>{{ user.totalEarned }}</dd></div>
            <div><dt>累计使用</dt><dd>{{ user.totalSpent }}</dd></div>
          </dl>
          <button v-if="admin" class="adjust-entry-btn" type="button" @click="emit('adjust', user)">调整积分</button>
        </section>

        <!-- ⑥ 审计时间线 -->
        <section class="drawer-section" data-testid="detail-section-audit" aria-label="审计时间线">
          <h4>审计时间线</h4>
          <p v-if="auditLoading" class="td-muted">加载中...</p>
          <template v-else>
            <p v-if="auditEntries.length === 0" class="td-muted" data-testid="audit-empty">暂无记录</p>
            <ol v-else class="audit-timeline">
              <li v-for="entry in auditEntries" :key="entry.id" class="audit-row">
                <span class="audit-action">{{ entry.action }}</span>
                <span class="td-time">{{ formatDateTime(entry.occurredAt) }}</span>
                <span class="audit-meta">{{ entry.ipAddress || '—' }}</span>
                <span class="audit-meta audit-ua" :title="entry.userAgent || ''">{{ entry.userAgent || '—' }}</span>
              </li>
            </ol>
          </template>
        </section>
      </aside>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { request } from '../../../composables/grassland-http'

/** 用户管理行数据（AdminView.UserItem 同构；此处独立声明避免循环依赖）。 */
export interface AdminUserRowData {
  id: string
  email: string
  displayName: string | null
  role: string
  status: string
  createdAt: string
  balance: number
  totalEarned: number
  totalSpent: number
  roles?: string[]
  identities?: {
    recommender: boolean
    merchant: boolean
    member: boolean
    ownedOrgNames: string | null
    ownedOrgs?: Array<{ id: string; name: string; status: string }>
  }
}

interface AuditEntry {
  id: string
  action: string
  ipAddress: string | null
  userAgent: string | null
  occurredAt: string | null
}

/** 七后台角色（PRD §11.8，固定枚举——只授予/回收，不新增定义）。 */
const BACKEND_ROLE_OPTIONS = [
  'platform_admin', 'merchant_reviewer', 'content_reviewer', 'customer_service',
  'finance', 'risk', 'ai_admin',
] as const

const props = defineProps<{
  user: AdminUserRowData | null
  /** platform_admin 专属区（角色管理/组织管控/调整积分入口）。 */
  admin: boolean
}>()

const emit = defineEmits<{
  close: []
  refresh: []
  adjust: [user: AdminUserRowData]
}>()

const USER_STATUS_META: Record<string, { label: string; badge: string }> = {
  active: { label: '正常', badge: 'badge-success' },
  suspended: { label: '已停用', badge: 'badge-danger' },
  pending_review: { label: '待复核', badge: 'badge-warning' },
  deleted: { label: '已删除', badge: 'badge-warning' },
}

const statusMeta = computed(() =>
  USER_STATUS_META[props.user?.status ?? ''] ?? { label: props.user?.status ?? '—', badge: 'badge-warning' })
const ownedOrgs = computed(() => props.user?.identities?.ownedOrgs ?? [])
const suspendedOwnedOrgs = computed(() => ownedOrgs.value.filter((org) => org.status === 'suspended'))
const hasAnyIdentity = computed(() => Boolean(props.user?.identities
  && (props.user.identities.recommender || props.user.identities.merchant || props.user.identities.member)))

// ---- 审计时间线（打开时拉一次，倒序） ----
const auditEntries = ref<AuditEntry[]>([])
const auditLoading = ref(false)

watch(() => props.user?.id, (id) => {
  auditEntries.value = []
  if (!id) return
  auditLoading.value = true
  void request<AuditEntry[]>(`/api/admin/users/${encodeURIComponent(id)}/audit`, {}, { fallbackError: '审计加载失败' })
    .then((entries) => {
      auditEntries.value = [...entries].sort((a, b) => (b.occurredAt ?? '').localeCompare(a.occurredAt ?? ''))
    })
    .catch(() => {
      auditEntries.value = []
    })
    .finally(() => {
      auditLoading.value = false
    })
}, { immediate: true })

// ---- 组织冻结/恢复（admin，二次确认） ----
const pendingOrgAction = ref<{ org: { id: string; name: string; status: string }; action: 'suspend' | 'restore' } | null>(null)
const orgSubmitting = ref(false)
const orgError = ref('')

async function executeOrgAction(): Promise<void> {
  const pending = pendingOrgAction.value
  if (!pending) return
  orgSubmitting.value = true
  orgError.value = ''
  try {
    await request(`/api/admin/organizations/${pending.org.id}/${pending.action}`, { method: 'POST' },
      { fallbackError: '操作失败' })
    pendingOrgAction.value = null
    emit('refresh')
  } catch (e: unknown) {
    orgError.value = e instanceof Error ? e.message : '操作失败'
  } finally {
    orgSubmitting.value = false
  }
}

// ---- 后台角色授予/回收（admin-only；授予 platform_admin 强确认） ----
const selectedRole = ref<string>('content_reviewer')
const roleSubmitting = ref(false)
const roleError = ref('')
const grantAdminConfirm = ref(false)

function grantOrRevoke(action: 'grant' | 'revoke'): void {
  if (action === 'grant' && selectedRole.value === 'platform_admin') {
    // 平台管理员是超集角色：授予必须二次确认（首次点击只亮出警示，不发请求）
    grantAdminConfirm.value = true
    return
  }
  grantAdminConfirm.value = false
  void submitRole(action)
}

function confirmGrantAdmin(): void {
  grantAdminConfirm.value = false
  void submitRole('grant')
}

async function submitRole(action: 'grant' | 'revoke'): Promise<void> {
  if (!props.user) return
  roleSubmitting.value = true
  roleError.value = ''
  try {
    await request(`/api/admin/users/${encodeURIComponent(props.user.id)}/roles`, {
      method: 'PUT',
      body: JSON.stringify({ action, role: selectedRole.value }),
    }, { fallbackError: '操作失败' })
    emit('refresh')
  } catch (e: unknown) {
    roleError.value = e instanceof Error ? e.message : '操作失败'
  } finally {
    roleSubmitting.value = false
  }
}

// Escape 关抽屉（OpsConsole 抽屉同款：全屏遮罩必须有键盘出路）
function onKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape') emit('close')
}

onMounted(() => window.addEventListener('keydown', onKeydown))
onBeforeUnmount(() => window.removeEventListener('keydown', onKeydown))

function formatDateTime(iso: string | null): string {
  if (!iso) return '-'
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return '-'
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
  }).format(date)
}
</script>

<style scoped>
/* 抽屉结构照抄 OpsConsole 处置单详情抽屉（#53 先例）：右滑面板 + 遮罩。 */
.drawer-mask {
  position: fixed;
  inset: 0;
  background: color-mix(in srgb, var(--color-bg) 55%, transparent);
  backdrop-filter: blur(4px);
  display: flex;
  justify-content: flex-end;
  z-index: 40;
}

.user-drawer {
  width: min(560px, 94vw);
  height: 100%;
  overflow-y: auto;
  padding: var(--space-md);
  background: var(--color-surface);
  border-left: 1px solid var(--color-border);
  display: flex;
  flex-direction: column;
  gap: var(--space-sm);
}

.drawer-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: var(--space-xs);
}

.drawer-head h3 {
  margin: 0;
  font-size: 15px;
}

.drawer-close {
  min-height: 32px;
  padding: 0 var(--space-sm);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--color-text-secondary);
  font-size: var(--text-xs);
  cursor: pointer;
}

.drawer-section {
  display: grid;
  gap: var(--space-xs);
  padding: var(--space-sm);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
}

.drawer-section h4 {
  margin: 0;
  font-size: 13px;
  color: var(--color-text-secondary);
}

.section-note {
  margin: 0;
  font-size: 12px;
  color: var(--color-text-muted);
  line-height: 1.6;
}

.detail-kv {
  display: grid;
  grid-template-columns: 64px minmax(0, 1fr);
  gap: 6px var(--space-sm);
  margin: 0;
  font-size: 0.82rem;
}

.detail-kv-3 {
  grid-template-columns: repeat(3, minmax(0, 1fr));
}

.detail-kv dt {
  color: var(--color-text-muted);
}

.detail-kv dd {
  margin: 0;
  color: var(--color-text);
  overflow-wrap: anywhere;
}

.dd-id {
  font-family: var(--font-mono);
  font-size: 0.76rem;
}

.dd-email {
  font-weight: 500;
}

.dd-balance {
  font-weight: 700;
  color: var(--color-accent);
}

.identity-list,
.org-list,
.audit-timeline {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-size: 0.82rem;
}

.identity-list li,
.org-list li {
  display: flex;
  align-items: center;
  gap: var(--space-xs);
  flex-wrap: wrap;
}

.org-suspended {
  opacity: 0.62;
}

.org-name {
  font-weight: 500;
}

.org-btn {
  padding: 3px 10px;
  border-radius: var(--radius-sm);
  border: 1px solid var(--color-border);
  background: transparent;
  font-size: 0.76rem;
  cursor: pointer;
}

.org-freeze-btn {
  border-color: color-mix(in srgb, var(--color-danger) 30%, transparent);
  color: var(--color-danger);
}

.org-restore-btn {
  border-color: color-mix(in srgb, var(--color-success) 35%, transparent);
  color: var(--color-success);
}

.confirm-row {
  display: flex;
  align-items: center;
  gap: var(--space-xs);
  flex-wrap: wrap;
  padding: var(--space-xs) var(--space-sm);
  border: 1px solid color-mix(in srgb, var(--color-warning) 35%, transparent);
  border-radius: var(--radius-sm);
  background: color-mix(in srgb, var(--color-warning) 8%, transparent);
  font-size: 0.78rem;
  color: var(--color-text);
}

.role-chips {
  display: flex;
  gap: 4px;
  flex-wrap: wrap;
}

.role-editor {
  display: flex;
  align-items: center;
  gap: var(--space-xs);
  flex-wrap: wrap;
}

.role-select {
  min-height: 34px;
  padding: 0 var(--space-xs);
  border: 1px solid var(--color-border);
  background: transparent;
  color: var(--color-text);
  border-radius: var(--radius-sm);
  font-size: var(--text-sm);
  cursor: pointer;
}

.grant-btn,
.revoke-btn,
.adjust-entry-btn {
  min-height: 32px;
  padding: 0 var(--space-sm);
  border-radius: var(--radius-sm);
  border: 1px solid var(--color-border);
  background: transparent;
  font-size: 0.78rem;
  cursor: pointer;
}

.grant-btn {
  color: var(--color-accent);
}

.revoke-btn {
  color: var(--color-danger);
  border-color: color-mix(in srgb, var(--color-danger) 30%, transparent);
}

.adjust-entry-btn {
  color: var(--color-accent);
  justify-self: start;
}

.audit-timeline {
  gap: 8px;
}

.audit-row {
  display: flex;
  align-items: center;
  gap: var(--space-xs);
  flex-wrap: wrap;
  font-size: 12px;
}

.audit-action {
  font-weight: 500;
}

.audit-meta {
  color: var(--color-text-muted);
}

.audit-ua {
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  flex: 1;
}

.td-muted {
  color: var(--color-text-muted);
  font-size: 0.82rem;
}

.td-time {
  white-space: nowrap;
  color: var(--color-text-muted);
}

.error-msg {
  margin: 0;
  padding: var(--space-xs) var(--space-sm);
  border-radius: var(--radius-sm);
  background: color-mix(in srgb, var(--color-danger) 10%, transparent);
  border: 1px solid color-mix(in srgb, var(--color-danger) 20%, transparent);
  color: var(--color-danger);
  font-size: 0.8rem;
}
</style>
