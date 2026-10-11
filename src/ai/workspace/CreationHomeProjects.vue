<template>
  <section class="workspace-resume" aria-labelledby="workspace-resume-title">
    <div class="workspace-section-head">
      <h2 id="workspace-resume-title">继续创作</h2>
      <RouterLink class="gl-btn-secondary workspace-resume-all" :to="{ name: 'projects' }">查看全部项目</RouterLink>
    </div>

    <p v-if="projectsError" class="workspace-resume-message" role="alert">
      {{ projectsError }}
      <button type="button" class="gl-btn-secondary workspace-resume-retry" @click="workspace.loadProjects()">重试</button>
    </p>
    <p v-else-if="loading" class="workspace-resume-message" role="status">正在载入最近创作…</p>

    <template v-else>
      <div v-if="recentProjects.length" class="workspace-resume-grid">
        <article v-for="item in recentProjects" :key="item.id" class="workspace-resume-card">
          <div class="workspace-resume-card-head">
            <span v-if="platformText(item)" class="badge badge-accent">{{ platformText(item) }}</span>
            <span class="badge" :class="item.status === 'in_progress' ? 'badge-info' : 'badge-neutral'">{{ statusText(item) }}</span>
          </div>
          <h3>{{ item.title || '未命名创作' }}</h3>
          <div class="workspace-resume-card-foot">
            <span class="gl-hint">{{ [capabilityText(item), relativeTime(item.updatedAt)].filter(Boolean).join(' · ') }}</span>
            <button type="button" class="gl-btn-primary" :disabled="pendingId === item.id" @click="continueItem(item)">
              {{ pendingId === item.id ? '恢复中…' : '继续' }}
            </button>
          </div>
        </article>
      </div>
      <EmptyState v-else title="还没有进行中的创作" description="在上面写一句话就能开始；写过的每一篇会停在这里等你回来。" />
    </template>
  </section>
</template>
<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import EmptyState from '../../components/shared/EmptyState.vue'
import { CAPABILITY_LABELS, projectContinueRoute, useCreationWorkspace } from '../../lib/creation-workspace'
import { platformDisplayLabel } from '../../config/ai-platform-capabilities'
import type { CreationProject, CreationProjectStatus } from '../../types/creation'

/**
 * 继续创作（2026-10 重设计）：登录后首屏先给「回到现场」，再给新建入口。
 * 数据复用最近项目列表（C-03，服务端权威）；恢复走 §10.2 交接协议——
 * loadProject 取权威草稿 → setPendingContinue → 按 projectContinueRoute 跳落点，
 * 与 AiCreationCenter.continueProject 同口径。
 */
const workspace = useCreationWorkspace()
const router = useRouter()
const projects = workspace.projects
const loading = workspace.projectsLoading
const projectsError = workspace.projectsError
const pendingId = ref('')
const recentProjects = computed(() => projects.value.slice(0, 3))

onMounted(() => { if (!projects.value.length) void workspace.loadProjects() })

const STATUS_LABELS: Record<CreationProjectStatus, string> = {
  draft: '草稿', in_progress: '进行中', completed: '已完成', archived: '已归档',
}

function platformText(item: CreationProject): string {
  return platformDisplayLabel(item.platform ?? '')
}
function statusText(item: CreationProject): string {
  return STATUS_LABELS[item.status] || '草稿'
}
function capabilityText(item: CreationProject): string {
  return CAPABILITY_LABELS[item.capability] || '创作'
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

async function continueItem(item: CreationProject): Promise<void> {
  if (pendingId.value) return
  pendingId.value = item.id
  try {
    const draft = await workspace.loadProject(item.id)
    if (!draft) {
      workspace.removeLocal(item.id) // 404/无权：从列表移除且不泄露原因（§6.4）
      return
    }
    workspace.setPendingContinue(draft)
    const target = projectContinueRoute(draft, { aiApp: document.documentElement.dataset.app === 'ai' })
    if (target) await router.push(target)
  } finally {
    pendingId.value = ''
  }
}
</script>
