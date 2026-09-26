<script setup lang="ts">
/**
 * ProjectList.vue — C107-21：工程列表（输入 items/loading/error；事件
 * open/create/delete）。不保存全局工程状态（只投影 props）。
 */
import { ref } from 'vue';
import EmptyState from '../../../components/shared/EmptyState.vue';
import type { HypitProject } from '../../../types/hypit';

const props = defineProps<{
  items: HypitProject[];
  loading: boolean;
  error: string | null;
  activeProjectId: string | null;
}>();

const emit = defineEmits<{
  open: [projectId: string];
  create: [];
  remove: [projectId: string];
}>();

const confirmDeleteId = ref<string | null>(null);

function statusLabel(status: HypitProject['status']): string {
  const labels: Record<HypitProject['status'], string> = {
    provisioning: '准备中',
    ready: '就绪',
    deleting: '删除中',
    deleted: '已删除',
    provisioning_failed: '创建失败',
  };
  return labels[status];
}
</script>

<template>
  <section class="gl-zone" data-testid="clone-project-list" aria-label="视频克隆工程列表">
    <header class="clone-list-head">
      <h2>我的工程</h2>
      <button type="button" class="gl-btn-primary" data-testid="clone-new-project" :disabled="props.loading"
        @click="emit('create')">新建工程</button>
    </header>
    <p v-if="props.error" class="clone-error" data-testid="clone-error" role="alert">{{ props.error }}</p>
    <p v-else-if="props.loading" class="clone-loading" data-testid="clone-loading" aria-live="polite">正在加载工程…</p>
    <EmptyState v-else-if="props.items.length === 0" data-testid="clone-empty"
      title="还没有视频克隆工程" description="从参考视频、模板或想法开始你的第一个工程。" />
    <ul v-else class="clone-project-items">
      <li v-for="project in props.items" :key="project.id"
        :class="['clone-project-item', { 'clone-project-item--active': project.id === props.activeProjectId }]">
        <button type="button" class="clone-project-open" @click="emit('open', project.id)">
          <span class="clone-project-title">{{ project.title }}</span>
          <span class="clone-project-meta">rev {{ project.revision }} · {{ statusLabel(project.status) }}</span>
        </button>
        <span v-if="confirmDeleteId === project.id" class="clone-project-confirm">
          <span>删除该工程？</span>
          <button type="button" class="gl-btn-primary clone-btn-danger" @click="emit('remove', project.id); confirmDeleteId = null">确认删除</button>
          <button type="button" class="gl-btn-secondary" @click="confirmDeleteId = null">取消</button>
        </span>
        <button v-else type="button" class="gl-btn-ghost clone-project-delete"
          :aria-label="`删除工程 ${project.title}`" @click="confirmDeleteId = project.id">删除</button>
      </li>
    </ul>
  </section>
</template>

<style scoped>
.clone-list-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 12px; }
.clone-list-head h2 { margin: 0; font-family: var(--font-display); font-size: 16px; }
.clone-error { color: var(--color-danger); }
.clone-loading { color: var(--color-text-secondary); }
.clone-project-items { list-style: none; margin: 0; padding: 0; display: grid; gap: 8px; }
.clone-project-item { display: flex; align-items: center; gap: 8px; border: 1px solid var(--color-border); border-radius: var(--radius-md); padding: 8px 12px; }
.clone-project-item--active { border-color: var(--color-primary); }
.clone-project-open { flex: 1; display: flex; flex-direction: column; align-items: flex-start; gap: 2px; background: none; border: 0; cursor: pointer; text-align: left; color: inherit; padding: 0; }
.clone-project-title { font-weight: 600; }
.clone-project-meta { font-size: 12px; color: var(--color-text-secondary); }
.clone-project-confirm { display: flex; align-items: center; gap: 6px; font-size: 13px; }
</style>
