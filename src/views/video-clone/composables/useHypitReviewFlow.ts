/**
 * useHypitReviewFlow.ts — C107-21 (W21 拆分，§13.3 登记)；107-fix-2 C107F2-25 重写：
 * 「按评论修改」走 Agent revise intent job（§6.11）——commentIds/run 经机器可读
 * brief 传给服务端语义修改（不再前端拼 content/baseHash=null 整文件覆盖）；
 * 终态 succeeded 后刷新评论（服务端已批量 resolve）。
 */
import { onUnmounted, ref } from 'vue';
import {
  mutateFeedback,
  readFeedback,
} from './hypit-api';
import type { RefreshGate } from './useHypitProjectScope';
import type { HypitFeedbackComment } from '../../../types/hypit';

export function useHypitReviewFlow(observers: {
  watchJob?: (projectId: string, jobId: string, onTerminal: (terminal: { state: string; blockedReason?: string | null }) => void) => void;
} = {}) {
  const comments = ref<HypitFeedbackComment[]>([]);
  const hash = ref('');
  const loading = ref(false);
  const error = ref<string | null>(null);
  const revising = ref(false);
  const controller = new AbortController();
  let token = 0;

  async function refresh(projectId: string, gate?: RefreshGate): Promise<void> {
    const mine = ++token;
    const signal = gate?.signal ?? controller.signal;
    const ok = () => mine === token && !signal.aborted && (gate?.isCurrent?.() ?? true);
    loading.value = true;
    error.value = null;
    try {
      const view = await readFeedback(projectId, undefined, signal);
      if (!ok()) return;
      comments.value = view.comments;
      hash.value = view.hash;
    } catch (cause) {
      if (!ok()) return;
      error.value = (cause as Error).message;
    } finally {
      if (ok()) loading.value = false;
    }
  }

  async function mutate(projectId: string, mutations: Record<string, unknown>[]): Promise<boolean> {
    error.value = null;
    try {
      const applied = await mutateFeedback(projectId, {
        requestId: crypto.randomUUID(),
        expectedHash: hash.value,
        mutations,
      }, controller.signal);
      comments.value = applied.comments;
      hash.value = applied.hash;
      return true;
    } catch (cause) {
      if (controller.signal.aborted) return false;
      error.value = (cause as Error).message;
      return false;
    }
  }

  function add(projectId: string, run: string, text: string, at: number): Promise<boolean> {
    return mutate(projectId, [{
      type: 'add',
      comment: { id: crypto.randomUUID(), run, at, text },
    }]);
  }

  function resolve(projectId: string, comment: HypitFeedbackComment): Promise<boolean> {
    return mutate(projectId, [{ type: 'replace', before: comment, comment: { ...comment, resolved: true } }]);
  }

  /**
   * C107F2-25：按未解决评论修订——创建 Agent revise intent job（commentIds/run 走
   * 机器可读 brief；baseRevision 一等参数），watch 终态：succeeded→刷新（服务端已
   * resolve 落地评论）；waiting_input→错误显示需澄清原因；failed→如实上报。
   */
  async function revise(projectId: string, baseRevision: number): Promise<boolean> {
    revising.value = true;
    error.value = null;
    const targets = comments.value.filter((comment) => !comment.resolved);
    if (targets.length === 0) {
      revising.value = false;
      return false;
    }
    const run = targets[0]?.run ?? 'main.svrun';
    const brief = JSON.stringify({
      commentIds: targets.map((comment) => comment.id),
      run,
      summary: `按 ${targets.length} 条评论做语义源码修改`,
    });
    try {
      const receipt = await import('./hypit-api').then((api) => api.hypitRequest<{ jobId: string }>(
        `/projects/${projectId}/agent-jobs`, {
          method: 'POST',
          headers: { 'content-type': 'application/json' },
          body: JSON.stringify({
            requestId: crypto.randomUUID(),
            intent: 'revise',
            brief,
            assetIds: [],
            baseRevision,
          }),
          signal: controller.signal,
        },
      ));
      return await new Promise<boolean>((resolveOutcome) => {
        observers.watchJob?.(projectId, receipt.jobId, (terminal) => {
          void (async () => {
            if (terminal.state === 'succeeded') {
              await refresh(projectId);
              revising.value = false;
              resolveOutcome(true);
              return;
            }
            revising.value = false;
            error.value = terminal.blockedReason ?? `修订未完成（${terminal.state}）`;
            resolveOutcome(false);
          })();
        });
      });
    } catch (cause) {
      if (controller.signal.aborted) return false;
      error.value = (cause as Error).message;
      return false;
    } finally {
      // 终态回调负责关闭 revising；异常路径兜底。
      window.setTimeout(() => { revising.value = false; }, 0);
    }
  }

  /** 确认离开当前工程时清空评论域状态（服务端 FEEDBACK 原样保留）。 */
  function reset(): void {
    token += 1;
    comments.value = [];
    hash.value = '';
    loading.value = false;
    error.value = null;
  }

  onUnmounted(() => controller.abort());

  return { comments, hash, loading, error, revising, refresh, add, resolve, revise, reset };
}
