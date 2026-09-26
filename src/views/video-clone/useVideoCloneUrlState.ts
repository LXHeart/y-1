/**
 * useVideoCloneUrlState.ts — C107-21 (task-107) URL 状态（W21 固定职责）：
 * project/step/run/job/asset 的解析与恢复；敏感或非法 query 一律忽略（URL
 * 不是权限来源）。step 只接受服务端真实阶段名。
 */
import { computed, ref, watch, type Ref } from 'vue';
import { useRoute, useRouter, type Router, type RouteLocationNormalizedLoaded } from 'vue-router';

export type VideoCloneStep = 'reference' | 'plan' | 'generate' | 'review';

const STEPS: readonly VideoCloneStep[] = ['reference', 'plan', 'generate', 'review'];

function safeStep(raw: unknown): VideoCloneStep | null {
  return typeof raw === 'string' && (STEPS as readonly string[]).includes(raw) ? (raw as VideoCloneStep) : null;
}

/** 服务端标识形状：uuid 或受限 slug；其余整段丢弃。 */
function safeId(raw: unknown): string | null {
  if (typeof raw !== 'string' || raw.length === 0 || raw.length > 64) return null;
  return /^[a-zA-Z0-9][a-zA-Z0-9._-]*$/u.test(raw) ? raw : null;
}

export function useVideoCloneUrlState(
  routeOverride?: RouteLocationNormalizedLoaded,
  routerOverride?: Router,
) {
  const route = routeOverride ?? useRoute();
  const router = routerOverride ?? useRouter();

  const projectId = computed(() => safeId(route.params.projectId));
  const step = ref<VideoCloneStep>(safeStep(route.query.step) ?? 'reference');
  const runId = ref<string | null>(safeId(route.query.run));
  const jobId = ref<string | null>(safeId(route.query.job));
  const assetId = ref<string | null>(safeId(route.query.asset));

  watch(() => route.query.step, (value) => {
    const next = safeStep(value);
    if (next !== null) step.value = next;
  });

  /** 深链更新：只写服务端标识字段，不携带凭据或绝对路径。 */
  function update(next: Partial<{ step: VideoCloneStep; run: string | null; job: string | null; asset: string | null }>): Promise<void> {
    if (next.step !== undefined) step.value = next.step;
    if (next.run !== undefined) runId.value = next.run;
    if (next.job !== undefined) jobId.value = next.job;
    if (next.asset !== undefined) assetId.value = next.asset;
    const query: Record<string, string> = { ...route.query } as Record<string, string>;
    if (step.value !== 'reference') query.step = step.value;
    else delete query.step;
    for (const key of ['run', 'job', 'asset'] as const) {
      const value = { run: runId.value, job: jobId.value, asset: assetId.value }[key];
      if (value === null) delete query[key];
      else query[key] = value;
    }
    return router.replace({ query }).then(() => undefined);
  }

  /** 切工程：清空非工程状态并回到首阶段。 */
  function resetForProject(): void {
    step.value = 'reference';
    runId.value = null;
    jobId.value = null;
    assetId.value = null;
  }

  return {
    projectId,
    step: step as Ref<VideoCloneStep>,
    runId,
    jobId,
    assetId,
    update,
    resetForProject,
  };
}
