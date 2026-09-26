<script setup lang="ts">
/**
 * AgentActivityPanel.vue — C107-21：Agent 活动与可恢复问题（输入 job/actions；
 * 事件 resume/cancel）。不根据文字猜测成功——只渲染服务端真实阶段/动作行。
 */
import { computed, onMounted, onUnmounted, ref } from 'vue';
import { hypitRequest } from '../composables/hypit-api';
import type { HypitJob } from '../../../types/hypit';

const props = defineProps<{
  projectId: string;
  job: HypitJob | null;
  events: { sequence: number; kind: string; data: Record<string, unknown> }[];
  connected: boolean;
}>();

const emit = defineEmits<{ cancel: [] }>();

interface ActionRow {
  stepIndex: number;
  kind: string;
  state: string;
}

const actions = ref<ActionRow[]>([]);
const actionsError = ref<string | null>(null);
const controller = new AbortController();

async function loadActions(): Promise<void> {
  if (props.job === null) return;
  actionsError.value = null;
  try {
    const page = await hypitRequest<{ actions: ActionRow[] }>(
      `/projects/${props.projectId}/jobs/${props.job.id}/actions`, {
      method: 'GET', signal: controller.signal,
    });
    actions.value = page.actions;
  } catch (cause) {
    if (controller.signal.aborted) return;
    actionsError.value = (cause as Error).message;
  }
}

onMounted(loadActions);
onUnmounted(() => controller.abort());

const phaseText = computed(() => {
  if (props.job === null) return '';
  if (props.job.blockedReason !== null) return `等待输入：${props.job.blockedReason}`;
  return props.job.phase ?? props.job.state;
});
</script>

<template>
  <section class="gl-zone" data-testid="clone-agent-panel" aria-label="Agent 活动">
    <header class="clone-agent-head">
      <h2>Agent 活动</h2>
      <span v-if="props.job !== null" class="clone-agent-connection" :data-connected="String(props.connected)">
        {{ props.connected ? '实时连接中' : '连接中断（自动重连）' }}
      </span>
    </header>
    <p v-if="props.job === null" class="clone-empty">当前没有运行中的 Agent 任务。</p>
    <template v-else>
      <p class="clone-agent-phase" data-testid="clone-job-running" role="status">{{ phaseText }}</p>
      <p v-if="actionsError" class="clone-error" role="alert">{{ actionsError }}</p>
      <ul v-if="actions.length > 0" class="clone-agent-actions">
        <li v-for="(action, index) in actions" :key="index" class="clone-agent-action" :data-state="action.state">
          <span class="clone-agent-step">step {{ action.stepIndex }}</span>
          <span class="clone-agent-kind">{{ action.kind }}</span>
          <span class="clone-agent-state">{{ action.state }}</span>
        </li>
      </ul>
      <ol v-if="props.events.length > 0" class="clone-agent-events" aria-label="任务事件">
        <li v-for="event in props.events.slice(-8)" :key="event.sequence">
          <span class="clone-agent-event-kind">{{ event.kind }}</span>
          <code class="clone-agent-event-data">{{ JSON.stringify(event.data).slice(0, 160) }}</code>
        </li>
      </ol>
      <button v-if="props.job.state === 'running' || props.job.state === 'queued'"
        type="button" class="gl-btn-secondary" data-testid="clone-job-cancel" @click="emit('cancel')">
        取消任务
      </button>
    </template>
  </section>
</template>

<style scoped>
.clone-agent-head { display: flex; align-items: center; justify-content: space-between; }
.clone-agent-head h2 { margin: 0; font-size: 16px; font-family: var(--font-display); }
.clone-agent-connection { font-size: 12px; color: var(--color-text-secondary); }
.clone-agent-connection[data-connected='false'] { color: var(--color-warning, #d8a024); }
.clone-empty { color: var(--color-text-secondary); }
.clone-agent-phase { font-weight: 600; }
.clone-error { color: var(--color-danger); }
.clone-agent-actions { list-style: none; margin: 8px 0; padding: 0; display: grid; gap: 4px; }
.clone-agent-action { display: flex; gap: 8px; font-size: 13px; }
.clone-agent-action[data-state='failed'] .clone-agent-state { color: var(--color-danger); }
.clone-agent-step { color: var(--color-text-secondary); font-variant-numeric: tabular-nums; }
.clone-agent-events { list-style: none; margin: 8px 0; padding: 0; display: grid; gap: 4px; font-size: 12px; }
.clone-agent-event-kind { font-weight: 600; margin-right: 6px; }
.clone-agent-event-data { color: var(--color-text-secondary); overflow-wrap: anywhere; }
</style>
