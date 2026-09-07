<template>
  <section class="recent-projects gl-field" aria-labelledby="recent-projects-title">
    <div class="gl-zone-head">
      <h3 id="recent-projects-title" class="gl-zone-title">最近项目</h3>
      <p class="gl-zone-note">按更新时间排序 · 点击继续恢复创作现场</p>
    </div>

    <p v-if="projectsError" class="error-state" role="alert">
      <span>{{ projectsError }}</span>
      <button type="button" class="secondary-command" data-testid="recent-retry" @click="workspace.loadProjects()">重试</button>
    </p>

    <div v-else-if="projectsLoading" class="inline-state">
      <p>正在载入最近项目…</p>
    </div>

    <template v-else>
      <!-- 撤销条独立于列表空态：归档最后一项后仍可一次撤销（§8.4） -->
      <p v-if="undoNotice" class="undo-bar" aria-live="polite" data-testid="recent-undo">
        <span>已从最近项目移除「{{ undoNotice.title }}」</span>
        <button
          type="button"
          class="secondary-command"
          :disabled="undoing"
          data-testid="recent-undo-action"
          @click="undoArchive"
        >{{ undoing ? '恢复中…' : '撤销' }}</button>
      </p>

      <div v-if="!projects.length" class="empty-state" data-testid="recent-empty">
        <strong>还没有创作项目</strong>
        <p>去「开始创作」选好平台与内容形式——首次输入后会自动保存，并出现在这里。</p>
      </div>

      <ul v-else class="project-list" data-testid="recent-list" aria-label="最近项目列表">
        <li v-for="item in projects" :key="item.id" class="project-item" :data-project-id="item.id">
          <span class="project-capability" :data-capability="item.capability">{{ capabilityLabel(item.capability) }}</span>
          <div class="project-main">
            <strong class="project-title">{{ item.title }}</strong>
            <span class="project-meta">
              <span v-if="sourceText(item)">{{ sourceText(item) }}</span>
              <time :datetime="item.updatedAt">{{ relativeTime(item.updatedAt) }}</time>
            </span>
          </div>
          <span class="project-status" :data-status="item.status">{{ statusLabel(item.status) }}</span>
          <div class="project-actions">
            <button
              type="button"
              class="secondary-command continue-command"
              data-testid="recent-continue"
              @click="emit('continue', item)"
            >继续创作</button>
            <button
              v-if="confirmingId !== item.id"
              type="button"
              class="secondary-command"
              :aria-label="`删除项目 ${item.title}`"
              @click="armConfirm(item)"
            >删除</button>
            <button
              v-else
              type="button"
              class="confirm-command"
              :aria-label="`确认删除项目 ${item.title}`"
              data-testid="recent-confirm-delete"
              @click="archiveItem(item)"
            >确认删除</button>
          </div>
        </li>
      </ul>
    </template>
  </section>
</template>

<script setup lang="ts">
import { onMounted, onScopeDispose, ref } from 'vue'
import { CAPABILITY_LABELS, useCreationWorkspace, workspaceOf } from '../../../lib/creation-workspace'
import type { CreationProject, CreationProjectCapability, CreationProjectStatus } from '../../../types/creation'

/**
 * 最近项目列表（任务书 #92 C-03）。数据与服务端权威（D-02），本组件只做展示缓存消费；
 * 「继续创作」只发事件由页面派发导航（不自动生成），删除＝归档（二次确认 + 一次撤销）。
 */
const emit = defineEmits<{ continue: [item: CreationProject] }>()

const workspace = useCreationWorkspace()
const projects = workspace.projects
const projectsLoading = workspace.projectsLoading
const projectsError = workspace.projectsError

const confirmingId = ref('')
const undoNotice = ref<{ id: string; title: string; previousStatus: CreationProjectStatus } | null>(null)
const undoing = ref(false)
let confirmTimer: ReturnType<typeof setTimeout> | null = null
let undoTimer: ReturnType<typeof setTimeout> | null = null

onMounted(() => {
  void workspace.loadProjects()
})

onScopeDispose(() => {
  if (confirmTimer) clearTimeout(confirmTimer)
  if (undoTimer) clearTimeout(undoTimer)
})

function capabilityLabel(capability: CreationProjectCapability): string {
  return CAPABILITY_LABELS[capability] || '创作'
}

function statusLabel(status: CreationProjectStatus): string {
  const labels: Record<CreationProjectStatus, string> = {
    draft: '草稿', in_progress: '进行中', completed: '已完成', archived: '已归档',
  }
  return labels[status] || '草稿'
}

/** 来源展示：工作区 sourceLabel 优先，缺省回退门店/任务 ID 截断态。 */
function sourceText(item: CreationProject): string {
  const payload = workspaceOf(item)
  if (payload.sourceLabel) return payload.sourceLabel
  const fallback = payload.source?.taskId || payload.source?.storeId || item.taskId || item.storeId || ''
  if (!fallback) return ''
  return fallback.length > 12 ? `${fallback.slice(0, 12)}…` : fallback
}

function relativeTime(iso: string): string {
  const at = new Date(iso).getTime()
  if (!Number.isFinite(at)) return ''
  const minutes = Math.floor((Date.now() - at) / 60_000)
  if (minutes < 1) return '刚刚'
  if (minutes < 60) return `${minutes} 分钟前`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours} 小时前`
  const days = Math.floor(hours / 24)
  if (days < 30) return `${days} 天前`
  return new Date(iso).toLocaleDateString('zh-CN')
}

/** 删除二次确认（§8.3）：首次点击武装，5s 内再点确认，超时自动撤销武装。 */
function armConfirm(item: CreationProject): void {
  confirmingId.value = item.id
  if (confirmTimer) clearTimeout(confirmTimer)
  confirmTimer = setTimeout(() => {
    if (confirmingId.value === item.id) confirmingId.value = ''
  }, 5_000)
}

async function archiveItem(item: CreationProject): Promise<void> {
  confirmingId.value = ''
  const result = await workspace.archiveProject(item.id)
  if (result === 'gone') {
    // 已被其他客户端归档/不存在：刷新列表对齐服务端（C-03 边界）
    void workspace.loadProjects()
    return
  }
  if (result === 'error') return
  workspace.removeLocal(item.id)
  undoNotice.value = { id: item.id, title: item.title, previousStatus: item.status }
  if (undoTimer) clearTimeout(undoTimer)
  undoTimer = setTimeout(() => {
    undoNotice.value = null
  }, 8_000)
}

/** 撤销归档（§8.4：GET 最新版本 + PUT 回原状态，不新增接口；仅一次）。 */
async function undoArchive(): Promise<void> {
  const notice = undoNotice.value
  if (!notice || undoing.value) return
  undoing.value = true
  const restored = await workspace.undoArchive(notice.id, notice.previousStatus)
  undoing.value = false
  if (restored) {
    undoNotice.value = null
    void workspace.loadProjects()
  }
}
</script>

<style scoped>
.recent-projects { display: grid; gap: var(--space-md); }
.inline-state, .empty-state { padding: var(--space-lg) 0; display: grid; gap: 6px; }
.inline-state p, .empty-state p { margin: 0; color: var(--color-text-muted); }
.empty-state { color: var(--color-text); }
.error-state { margin: 0; display: flex; align-items: center; justify-content: space-between; gap: 12px; color: var(--color-danger); font-size: 0.84rem; }
.undo-bar { margin: 0; display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 8px var(--space-sm); border: 1px solid var(--color-border-accent, var(--color-border)); border-radius: var(--radius-md); background: var(--surface-furrow); color: var(--color-text-secondary); font-size: var(--text-xs); }
.project-list { list-style: none; margin: 0; padding: 0; display: grid; gap: var(--space-sm); }
.project-item { display: flex; align-items: center; gap: var(--space-sm); padding: var(--space-sm); border: 1px solid var(--color-border); border-radius: var(--radius-md); background: var(--gradient-surface); }
.project-capability { flex-shrink: 0; padding: 4px 10px; border-radius: var(--radius-pill); background: color-mix(in srgb, var(--color-accent) 12%, transparent); color: var(--color-accent-2); font-size: var(--text-xs); font-weight: 600; }
.project-main { flex: 1; min-width: 0; display: grid; gap: 3px; }
.project-title { color: var(--color-text); font-size: 0.9rem; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.project-meta { display: flex; gap: 10px; flex-wrap: wrap; color: var(--color-text-muted); font-size: var(--text-xs); }
.project-status { flex-shrink: 0; padding: 3px 10px; border-radius: var(--radius-pill); border: 1px solid var(--color-border); color: var(--color-text-secondary); font-size: var(--text-xs); }
.project-status[data-status="completed"] { border-color: var(--color-border); background: var(--surface-furrow); }
.project-status[data-status="in_progress"] { color: var(--color-accent-2); }
.project-actions { flex-shrink: 0; display: flex; gap: 8px; }
.secondary-command, .confirm-command { min-height: 32px; padding: 0 12px; border-radius: var(--radius-sm); cursor: pointer; font-size: var(--text-xs); }
.secondary-command { border: 1px solid var(--color-border); background: transparent; color: var(--color-text-secondary); }
.secondary-command:hover { border-color: var(--color-border-hover); color: var(--color-text); }
.confirm-command { border: 1px solid transparent; background: var(--color-danger); color: var(--color-text); font-weight: 600; }
@media (max-width: 760px) {
  .project-item { flex-wrap: wrap; }
  .project-main { flex-basis: calc(100% - 60px); }
  .project-actions { width: 100%; }
  .project-actions .secondary-command, .project-actions .confirm-command { flex: 1; }
}
</style>
