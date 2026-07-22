<template>
  <section class="admin-view">
    <header class="section-header">
      <h2 class="section-title">用户管理</h2>
      <p class="section-desc">管理用户积分和查看使用情况</p>
    </header>

    <p v-if="loadError" class="error-msg">{{ loadError }}</p>

    <div v-if="loading" class="loading-state">加载中...</div>

    <div v-else class="table-card">
      <table class="user-table">
        <thead>
          <tr>
            <th>邮箱</th>
            <th>昵称</th>
            <th>角色</th>
            <th>积分余额</th>
            <th>累计获得</th>
            <th>累计使用</th>
            <th>注册时间</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="user in users" :key="user.id">
            <td class="td-email">{{ user.email }}</td>
            <td>{{ user.displayName || '-' }}</td>
            <td><span class="role-tag" :class="'role-' + user.role">{{ user.role }}</span></td>
            <td class="td-balance">{{ user.balance }}</td>
            <td>{{ user.totalEarned }}</td>
            <td>{{ user.totalSpent }}</td>
            <td class="td-time">{{ formatDate(user.createdAt) }}</td>
            <td>
              <button class="adjust-btn" type="button" @click="openAdjust(user)">调整积分</button>
            </td>
          </tr>
          <tr v-if="users.length === 0">
            <td colspan="8" class="td-empty">暂无用户</td>
          </tr>
        </tbody>
      </table>
    </div>

    <Teleport to="body">
      <div v-if="adjustTarget" class="modal-overlay" @click.self="adjustTarget = null">
        <div class="modal-card">
          <header class="modal-header">
            <h3 class="modal-title">调整积分 — {{ adjustTarget.email }}</h3>
            <button class="modal-close" type="button" @click="adjustTarget = null" aria-label="关闭">
              <svg width="16" height="16" viewBox="0 0 16 16" fill="none"><path d="M4 4l8 8M12 4l-8 8" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/></svg>
            </button>
          </header>

          <div class="modal-body">
            <p class="current-balance">当前积分：<strong>{{ adjustTarget.balance }}</strong></p>

            <label class="field-label">
              调整数量（正数增加，负数减少）
              <input v-model.number="adjustAmount" type="number" class="field-input" placeholder="例如：10 或 -5" />
            </label>

            <label class="field-label">
              备注
              <input v-model="adjustNote" type="text" class="field-input" placeholder="例如：手动充值" maxlength="200" />
            </label>

            <p v-if="adjustError" class="error-msg">{{ adjustError }}</p>

            <div class="modal-actions">
              <button class="btn-cancel" type="button" @click="adjustTarget = null">取消</button>
              <button class="btn-confirm" type="button" :disabled="adjusting" @click="handleAdjust">
                {{ adjusting ? '提交中...' : '确认调整' }}
              </button>
            </div>
          </div>
        </div>
      </div>
    </Teleport>
  </section>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'

interface UserItem {
  id: string
  email: string
  displayName: string | null
  role: string
  status: string
  createdAt: string
  balance: number
  totalEarned: number
  totalSpent: number
}

const users = ref<UserItem[]>([])
const loading = ref(false)
const loadError = ref('')

const adjustTarget = ref<UserItem | null>(null)
const adjustAmount = ref(0)
const adjustNote = ref('')
const adjusting = ref(false)
const adjustError = ref('')

onMounted(loadUsers)

async function loadUsers(): Promise<void> {
  loading.value = true
  loadError.value = ''
  try {
    const res = await fetch('/api/admin/users', { credentials: 'include' })
    if (!res.ok) {
      const data = await res.json().catch(() => null)
      throw new Error((data as Record<string, unknown>)?.error as string || '加载失败')
    }
    const data = await res.json() as { users: UserItem[] }
    users.value = data.users
  } catch (e: unknown) {
    loadError.value = e instanceof Error ? e.message : '加载失败'
  } finally {
    loading.value = false
  }
}

function openAdjust(user: UserItem): void {
  adjustTarget.value = user
  adjustAmount.value = 0
  adjustNote.value = ''
  adjustError.value = ''
}

async function handleAdjust(): Promise<void> {
  if (!adjustTarget.value) return
  if (adjustAmount.value === 0) {
    adjustError.value = '数量不能为 0'
    return
  }
  if (!adjustNote.value.trim()) {
    adjustError.value = '请输入备注'
    return
  }

  adjusting.value = true
  adjustError.value = ''

  try {
    const res = await fetch('/api/admin/adjust-credits', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      credentials: 'include',
      body: JSON.stringify({
        userId: adjustTarget.value.id,
        amount: adjustAmount.value,
        note: adjustNote.value.trim(),
      }),
    })
    if (!res.ok) {
      const data = await res.json().catch(() => null)
      throw new Error((data as Record<string, unknown>)?.error as string || '调整失败')
    }
    adjustTarget.value = null
    await loadUsers()
  } catch (e: unknown) {
    adjustError.value = e instanceof Error ? e.message : '调整失败'
  } finally {
    adjusting.value = false
  }
}

function formatDate(iso: string): string {
  const d = new Date(iso)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}
</script>

<style scoped>
.admin-view {
  display: grid;
  gap: var(--space-lg);
  max-width: 1000px;
  margin: 0 auto;
}

.section-header {
  display: grid;
  gap: var(--space-xs);
}

.section-title {
  font-size: 1.3rem;
  font-weight: 700;
  color: var(--color-text);
  margin: 0;
}

.section-desc {
  font-size: 0.88rem;
  color: var(--color-text-muted);
  margin: 0;
}

.loading-state {
  padding: var(--space-xl);
  text-align: center;
  color: var(--color-text-muted);
  font-size: 0.9rem;
}

.error-msg {
  padding: var(--space-sm) var(--space-md);
  border-radius: var(--radius-sm);
  background: rgba(239, 107, 107, 0.1);
  border: 1px solid rgba(239, 107, 107, 0.2);
  color: var(--color-danger);
  font-size: 0.86rem;
  margin: 0;
}

.table-card {
  padding: var(--space-lg);
  background: var(--gradient-surface);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-card);
  overflow-x: auto;
}

.user-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 0.86rem;
}

.user-table th {
  text-align: left;
  padding: 10px 12px;
  font-weight: 600;
  color: var(--color-text-muted);
  border-bottom: 1px solid var(--color-border);
  white-space: nowrap;
}

.user-table td {
  padding: 10px 12px;
  color: var(--color-text);
  border-bottom: 1px solid var(--color-border);
}

.user-table tbody tr:last-child td {
  border-bottom: none;
}

.td-email {
  font-weight: 500;
}

.td-balance {
  font-weight: 700;
  color: var(--color-accent);
}

.td-time {
  white-space: nowrap;
  color: var(--color-text-muted);
}

.td-empty {
  text-align: center;
  padding: var(--space-xl) !important;
  color: var(--color-text-muted);
}

.role-tag {
  display: inline-block;
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 0.78rem;
  font-weight: 600;
  text-transform: uppercase;
}

.role-admin {
  background: rgba(245, 158, 11, 0.15);
  color: #d97706;
}

.role-user {
  background: var(--surface-muted);
  color: var(--color-text-muted);
}

.adjust-btn {
  padding: 4px 12px;
  border: 1px solid var(--color-border);
  border-radius: 6px;
  background: transparent;
  color: var(--color-accent);
  font-size: 0.78rem;
  cursor: pointer;
  transition: all 0.15s ease-out;
}

.adjust-btn:hover {
  background: var(--surface-hover);
  border-color: var(--color-border-accent);
}

.modal-overlay {
  position: fixed;
  inset: 0;
  z-index: 100;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(0, 0, 0, 0.5);
  backdrop-filter: blur(4px);
}

.modal-card {
  width: min(440px, 92vw);
  background: var(--color-surface, #fff);
  border-radius: var(--radius-lg, 12px);
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.2);
}

.modal-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 20px;
  border-bottom: 1px solid var(--color-border, #e5e7eb);
}

.modal-title {
  font-size: 0.95rem;
  font-weight: 600;
  margin: 0;
  color: var(--color-text, #111);
}

.modal-close {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border: none;
  border-radius: 8px;
  background: transparent;
  color: var(--color-text-muted);
  cursor: pointer;
}

.modal-close:hover {
  background: var(--surface-hover, rgba(0, 0, 0, 0.05));
}

.modal-body {
  padding: 20px;
  display: grid;
  gap: 16px;
}

.current-balance {
  margin: 0;
  font-size: 0.88rem;
  color: var(--color-text-secondary);
}

.current-balance strong {
  color: var(--color-accent);
  font-size: 1.1rem;
}

.field-label {
  display: grid;
  gap: 6px;
  font-size: 0.84rem;
  color: var(--color-text-secondary);
}

.field-input {
  width: 100%;
  height: 40px;
  padding: 0 12px;
  border: 1px solid var(--color-border);
  border-radius: 8px;
  background: var(--surface-muted);
  color: var(--color-text);
  font-size: 0.88rem;
  box-sizing: border-box;
}

.field-input:focus {
  outline: none;
  border-color: var(--color-accent);
}

.modal-actions {
  display: flex;
  gap: 12px;
  justify-content: flex-end;
}

.btn-cancel {
  padding: 8px 16px;
  border: 1px solid var(--color-border);
  border-radius: 8px;
  background: transparent;
  color: var(--color-text-secondary);
  font-size: 0.86rem;
  cursor: pointer;
}

.btn-confirm {
  padding: 8px 20px;
  border: none;
  border-radius: 8px;
  background: var(--gradient-accent);
  color: #fff;
  font-size: 0.86rem;
  font-weight: 600;
  cursor: pointer;
}

.btn-confirm:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
</style>
