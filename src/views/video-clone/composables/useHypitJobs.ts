/**
 * useHypitJobs.ts — C107-21 (task-107) SSE/快照/清理（W21 固定职责）：
 * EventSource 按 sequence 去重、断线指数退避重连（有上限）、卸载/切工程时
 * 关闭连接与 timer；迟到的旧工程事件不会写进新状态（世代令牌）。
 */
import { onUnmounted, ref, watch, type Ref } from 'vue';
import type { HypitJob } from '../../../types/hypit';

export type JobWatch = {
  readonly job: Ref<HypitJob | null>;
  readonly events: Ref<{ sequence: number; kind: string; data: Record<string, unknown> }[]>;
  readonly connected: Ref<boolean>;
  readonly stop: () => void;
};

export function useHypitJobs() {
  const active = ref<JobWatch | null>(null);

  function watchJob(projectId: string, jobId: string, onTerminal?: (job: HypitJob) => void): JobWatch {
    active.value?.stop();
    const job = ref<HypitJob | null>(null);
    const events = ref<{ sequence: number; kind: string; data: Record<string, unknown> }[]>([]);
    const connected = ref(false);
    let closed = false;
    let retry = 0;
    let timer: ReturnType<typeof setTimeout> | null = null;
    let activeSource: EventSource | null = null;
    const stop = (): void => {
      closed = true;
      if (timer !== null) clearTimeout(timer);
      activeSource?.close();
      connected.value = false;
    };

    const connect = (): void => {
      if (closed) return;
      const source = new EventSource(`/api/hypit/projects/${projectId}/jobs/${jobId}/events`);
      source.onopen = () => {
        connected.value = true;
        retry = 0;
      };
      source.onerror = () => {
        connected.value = false;
        source.close();
        if (closed) return;
        // 指数退避重连，1.5s 起步、封顶 15s；关闭后不再重连。
        retry += 1;
        if (retry <= 8) {
          timer = setTimeout(connect, Math.min(15_000, 1_500 * 2 ** (retry - 1)));
        }
      };
      source.onmessage = (event) => {
        if (closed) return;
        try {
          const parsed = JSON.parse(event.data) as { sequence?: number; kind?: string; job?: HypitJob; data?: Record<string, unknown> };
          const sequence = typeof parsed.sequence === 'number' ? parsed.sequence : events.value.length + 1;
          // sequence 去重：同序号或更旧的事件直接丢弃。
          const lastEvent = events.value[events.value.length - 1];
          const last = lastEvent?.sequence ?? 0;
          if (sequence <= last) return;
          events.value.push({ sequence, kind: parsed.kind ?? 'progress', data: parsed.data ?? {} });
          if (parsed.job !== undefined && parsed.job !== null) {
            job.value = parsed.job;
            if (['succeeded', 'failed', 'cancelled'].includes(parsed.job.state)) {
              source.close();
              connected.value = false;
              onTerminal?.(parsed.job);
            }
          }
        } catch {
          // 损坏帧丢弃：不猜测状态。
        }
      };
      activeSource = source;
    };
    connect();

    const handle: JobWatch = { job, events, connected, stop };
    active.value = handle;
    return handle;
  }

  onUnmounted(() => {
    active.value?.stop();
    active.value = null;
  });

  return { active, watchJob, stop: () => { active.value?.stop(); active.value = null; } };
}

/** 工程切换守护：projectId 变化时停止旧观察（RISK-107-08 的第一道闸）。 */
export function stopOnProjectChange(watchers: { stop: () => void } | null, projectId: Ref<string | null>): void {
  watch(projectId, () => watchers?.stop());
}
