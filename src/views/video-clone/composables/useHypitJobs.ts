/**
 * useHypitJobs.ts — C107F2-13（F24/F06，§6.6）fetch 流式 SSE 订阅：
 * 完整 HypitSseEvent envelope、named event 白名单、Last-Event-ID 断线续接
 * （{jobId}:{sequence}）；多行/半包帧解析；1/2/4/8/15 秒退避最多 5 次后转
 * 手动重连；未登录停止重连；重复 sequence 丢弃、terminal 即停；stop 只断观
 * 察不冒充取消（服务端任务不受影响）。
 */
import { getCurrentInstance, onUnmounted, ref, watch, type Ref } from 'vue';
import { fetchApi } from '../../../composables/grassland-http';
import type { HypitJob, HypitJobState, HypitSseEvent } from '../../../types/hypit';

/** §6.6 named events；白名单外的 event 忽略（heartbeat 只证活不进列表）。 */
const ALLOWED_EVENTS: ReadonlySet<string> = new Set([
  'snapshot', 'progress', 'checkpoint', 'output', 'diagnostic', 'terminal',
]);
const RECONNECT_DELAYS_MS: readonly number[] = [1_000, 2_000, 4_000, 8_000, 15_000];
const TERMINAL_STATES: readonly HypitJobState[] = ['succeeded', 'failed', 'cancelled'];

export type SseFrame = { id: string | null; event: string | null; data: string };

/** 增量解析 SSE 帧：data: 可多行（换行拼接）；不足一帧的字节留在缓冲。 */
export function parseSseFrames(buffer: string): { frames: SseFrame[]; rest: string } {
  const frames: SseFrame[] = [];
  let rest = buffer;
  for (;;) {
    const boundary = rest.indexOf('\n\n');
    if (boundary < 0) break;
    const raw = rest.slice(0, boundary);
    rest = rest.slice(boundary + 2);
    const data: string[] = [];
    let id: string | null = null;
    let event: string | null = null;
    for (const line of raw.split('\n')) {
      if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''));
      else if (line.startsWith('id:')) id = line.slice(3).replace(/^ /, '');
      else if (line.startsWith('event:')) event = line.slice(6).replace(/^ /, '');
      // 注释（: 开头）与 retry 行忽略。
    }
    if (data.length > 0 || id !== null || event !== null) frames.push({ id, event, data: data.join('\n') });
  }
  return { frames, rest };
}

export type JobWatch = {
  readonly job: Ref<HypitJob | null>;
  readonly events: Ref<HypitSseEvent[]>;
  readonly connected: Ref<boolean>;
  readonly reconnectExhausted: Ref<boolean>;
  readonly authRequired: Ref<boolean>;
  readonly reconnect: () => void;
  readonly stop: () => void;
};

export function useHypitJobs() {
  const active = ref<JobWatch | null>(null);

  function watchJob(projectId: string, jobId: string, onTerminal?: (job: HypitJob) => void,
    initialJob?: HypitJob): JobWatch {
    active.value?.stop();
    const job = ref<HypitJob | null>(initialJob ?? null);
    const events = ref<HypitSseEvent[]>([]);
    const connected = ref(false);
    const reconnectExhausted = ref(false);
    const authRequired = ref(false);
    let closed = false;
    let terminated = false;
    let attempt = 0;
    let lastConfirmed: number | null = null;
    let timer: ReturnType<typeof setTimeout> | null = null;
    let controller: AbortController | null = null;

    const stop = (): void => {
      closed = true;
      if (timer !== null) clearTimeout(timer);
      timer = null;
      controller?.abort();
      controller = null;
      connected.value = false;
    };

    /** 手动重连：重置退避计数立即重试（自动重连用尽后用户触发）。 */
    const reconnect = (): void => {
      if (closed || terminated) return;
      reconnectExhausted.value = false;
      attempt = 0;
      if (timer !== null) clearTimeout(timer);
      timer = null;
      void open();
    };

    const scheduleRetry = (): void => {
      if (closed || terminated || authRequired.value) return;
      if (attempt >= RECONNECT_DELAYS_MS.length) {
        reconnectExhausted.value = true;
        connected.value = false;
        return;
      }
      const delay = RECONNECT_DELAYS_MS[attempt];
      attempt += 1;
      timer = setTimeout(() => {
        timer = null;
        void open();
      }, delay);
    };

    const finishTerminal = (_state: HypitJobState): void => {
      terminated = true;
      connected.value = false;
      controller?.abort();
      controller = null;
      if (job.value !== null) onTerminal?.(job.value);
    };

    const applyEvent = (envelope: HypitSseEvent): void => {
      if (closed || terminated) return;
      if (envelope.jobId !== undefined && envelope.jobId !== jobId) return;
      if (envelope.type === 'heartbeat') return;
      if (envelope.type === 'snapshot') {
        const data = envelope.data as { reset?: boolean; job?: HypitJob; latestSequence?: number } | null;
        if (data !== null && data.job !== undefined && data.job !== null) job.value = data.job;
        const latest = typeof data?.latestSequence === 'number' ? data.latestSequence : null;
        if (latest !== null && (lastConfirmed === null || latest > lastConfirmed)) lastConfirmed = latest;
        const state = job.value?.state;
        if (state !== undefined && (TERMINAL_STATES as readonly string[]).includes(state)) {
          finishTerminal(state);
        }
        return;
      }
      const sequence = typeof envelope.sequence === 'number' ? envelope.sequence : null;
      if (sequence !== null) {
        if (lastConfirmed !== null && sequence <= lastConfirmed) return;
        lastConfirmed = sequence;
      }
      events.value.push(envelope);
      if (envelope.type === 'terminal') {
        const state = (envelope.data as { state?: HypitJobState } | null)?.state;
        if (state !== undefined && job.value !== null) job.value = { ...job.value, state };
        if (state !== undefined && job.value !== null) {
          finishTerminal(state);
        } else {
          terminated = true;
          connected.value = false;
          controller?.abort();
          controller = null;
        }
      }
    };

    async function open(): Promise<void> {
      if (closed || terminated) return;
      controller = new AbortController();
      connected.value = false;
      try {
        const headers: Record<string, string> = { Accept: 'text/event-stream' };
        if (lastConfirmed !== null) headers['Last-Event-ID'] = `${jobId}:${lastConfirmed}`;
        const response = await fetchApi(`/api/hypit/projects/${projectId}/jobs/${jobId}/events`, {
          headers, signal: controller.signal,
        });
        if (response.status === 401 || response.status === 403) {
          // 未登录/无权：停止重连，由挂载点引导登录（§6.6）。
          authRequired.value = true;
          connected.value = false;
          return;
        }
        if (!response.ok || response.body === null) {
          scheduleRetry();
          return;
        }
        connected.value = true;
        attempt = 0;
        const reader = response.body.getReader();
        const decoder = new TextDecoder();
        let buffer = '';
        for (;;) {
          const { done, value } = await reader.read();
          if (closed || terminated) return;
          if (done) break;
          buffer = (buffer + decoder.decode(value, { stream: true })).replace(/\r\n/g, '\n');
          const parsed = parseSseFrames(buffer);
          buffer = parsed.rest;
          for (const frame of parsed.frames) {
            if (frame.event !== null && !ALLOWED_EVENTS.has(frame.event)) continue;
            if (frame.data === '') continue;
            try {
              applyEvent(JSON.parse(frame.data) as HypitSseEvent);
            } catch {
              // 损坏帧丢弃：不猜测状态。
            }
          }
        }
        connected.value = false;
        scheduleRetry();
      } catch {
        if (closed || terminated) return;
        connected.value = false;
        scheduleRetry();
      }
    }

    void open();

    const handle: JobWatch = { job, events, connected, reconnectExhausted, authRequired, reconnect, stop };
    active.value = handle;
    return handle;
  }

  // 组件外调用（测试）依赖返回的显式 stop()；仅组件内自动随卸载注销。
  if (getCurrentInstance()) onUnmounted(() => {
    active.value?.stop();
    active.value = null;
  });

  return { active, watchJob, stop: () => { active.value?.stop(); active.value = null; } };
}

/** 工程切换守护：projectId 变化时停止旧观察（RISK-107-08 的第一道闸）。 */
export function stopOnProjectChange(watchers: { stop: () => void } | null, projectId: Ref<string | null>): void {
  watch(projectId, () => watchers?.stop());
}
