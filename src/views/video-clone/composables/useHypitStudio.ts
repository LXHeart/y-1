/**
 * useHypitStudio.ts — C107F2-20 (task-107-fix-2) Studio 会话编排（W151）：
 * 按 generation 接收回执——每次 open 递增代数，迟到/旧代的响应一律丢弃；
 * 切工程（reset）先关旧会话再清本地态，旧 ticketUrl 绝不带进新工程；
 * close 走服务端 DELETE（幂等 closed:true），失败只提示不阻塞本地清场。
 */
import { onUnmounted, ref } from 'vue';
import { closeStudioSession, openStudioSession } from './hypit-api';
import type { HypitSessionCreated } from '../../../types/hypit';

export function useHypitStudio() {
  const session = ref<HypitSessionCreated | null>(null);
  const loading = ref(false);
  const error = ref<string | null>(null);
  /** 当前会话归属工程（close 需要它拼 DELETE 路径）。 */
  let projectId: string | null = null;
  /** 每次请求的代数：只接受最新一代的回执（§8.8 切工程/连点保护）。 */
  let generation = 0;
  const controller = new AbortController();

  async function open(project: string, body: {
    runFile?: string;
    revision?: number;
    readOnly?: boolean;
  } = {}): Promise<boolean> {
    const mine = ++generation;
    projectId = project;
    loading.value = true;
    error.value = null;
    // 打开新一代前先本地清旧（不等服务端；旧会话由其自身 TTL/撤销兜底）。
    session.value = null;
    try {
      const created = await openStudioSession(project, {
        requestId: crypto.randomUUID(),
        ...body,
      }, controller.signal);
      if (mine !== generation) return false; // 已被更新一代/切工程取代——丢弃旧回执。
      session.value = created;
      return true;
    } catch (cause) {
      if (controller.signal.aborted) return false;
      if (mine === generation) {
        error.value = (cause as Error).message;
        session.value = null;
      }
      return false;
    } finally {
      if (mine === generation) loading.value = false;
    }
  }

  /** 服务端关闭（幂等 closed:true）+ 本地清场；服务端失败不阻塞本地已关。 */
  async function close(): Promise<void> {
    const stale = session.value;
    const owner = projectId;
    session.value = null;
    error.value = null;
    if (stale === null || owner === null) return;
    try {
      await closeStudioSession(owner, stale.sessionId);
    } catch {
      // 离线/超时也保持本地已关（§8.4：关闭不要求在线成功）。
    }
  }

  /** 切工程：代数失效（在途回执作废）、服务端尽力关旧会话、清本地态。 */
  function reset(): void {
    generation += 1;
    const stale = session.value;
    const owner = projectId;
    projectId = null;
    session.value = null;
    error.value = null;
    if (stale !== null && owner !== null) {
      void closeStudioSession(owner, stale.sessionId).catch(() => undefined);
    }
  }

  onUnmounted(() => controller.abort());

  return { session, loading, error, open, close, reset };
}
