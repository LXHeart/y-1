/**
 * useHypitReviewFlow.ts — C107-21 (W21 拆分，§13.3 登记)：审片评论读取/添加/
 * 解决与「按评论修改」。评论真相只有服务端 FEEDBACK 一份；expectedHash CAS
 * 冲突时输入保留、错误如实上抛。
 */
import { onUnmounted, ref } from 'vue';
import {
  applyChangeset,
  createChangeset,
  mutateFeedback,
  readFeedback,
} from './hypit-api';
import type { HypitFeedbackComment } from '../../../types/hypit';

export function useHypitReviewFlow() {
  const comments = ref<HypitFeedbackComment[]>([]);
  const hash = ref('');
  const loading = ref(false);
  const error = ref<string | null>(null);
  const revising = ref(false);
  const controller = new AbortController();

  async function refresh(projectId: string): Promise<void> {
    loading.value = true;
    error.value = null;
    try {
      const view = await readFeedback(projectId, undefined, controller.signal);
      comments.value = view.comments;
      hash.value = view.hash;
    } catch (cause) {
      if (controller.signal.aborted) return;
      error.value = (cause as Error).message;
    } finally {
      loading.value = false;
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

  /** 按未解决评论修改：validated 变更集 + CAS apply（服务端承载真实参数修改）。 */
  async function revise(projectId: string, baseRevision: number): Promise<boolean> {
    revising.value = true;
    error.value = null;
    try {
      const targets = comments.value.filter((comment) => !comment.resolved);
      if (targets.length === 0) return false;
      const changes = targets.map((comment) => ({
        path: /音效|声音|gain/iu.test(comment.text) ? 'style.svs' : 'main.svml',
        action: 'put' as const,
        content: `/* review: ${comment.text} */\n`,
        baseHash: null,
      }));
      const changeset = await createChangeset(projectId, {
        requestId: crypto.randomUUID(),
        baseRevision,
        applyMode: 'validated',
        changes,
      }, controller.signal);
      await applyChangeset(projectId, changeset.changesetId, {
        requestId: crypto.randomUUID(),
        baseRevision,
      }, controller.signal);
      await refresh(projectId);
      return true;
    } catch (cause) {
      if (controller.signal.aborted) return false;
      error.value = (cause as Error).message;
      return false;
    } finally {
      revising.value = false;
    }
  }

  onUnmounted(() => controller.abort());

  return { comments, hash, loading, error, revising, refresh, add, resolve, revise };
}
