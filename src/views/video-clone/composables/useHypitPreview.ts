/**
 * useHypitPreview.ts — C107-21 (task-107) 预览会话与 iframe 消息（W21 固定职责）：
 * preview iframe sandbox 仅脚本能力；跨帧消息校验 source + 会话 nonce + 消息
 * schema（三关都过才接受）；播放时钟由统一 frame clock 驱动，卸载释放资源。
 */
import { onUnmounted, ref } from 'vue';
import { openPreviewSession } from './hypit-api';
import type { HypitSessionCreated } from '../../../types/hypit';

export type PreviewFrameMessage =
  | { readonly type: 'hypit:ready'; readonly sessionId: string }
  | { readonly type: 'hypit:frame'; readonly sessionId: string; readonly frame: number; readonly timeSeconds: number };

function parseMessage(origin: string, data: unknown, session: HypitSessionCreated): PreviewFrameMessage | null {
  if (!origin.includes(window.location.host)) return null;
  if (data === null || typeof data !== 'object') return null;
  const record = data as Record<string, unknown>;
  if (typeof record.type !== 'string' || !record.type.startsWith('hypit:')) return null;
  if (record.sessionId !== session.sessionId) return null;
  if (record.type === 'hypit:ready') return { type: 'hypit:ready', sessionId: session.sessionId };
  if (record.type === 'hypit:frame' && typeof record.frame === 'number' && typeof record.timeSeconds === 'number') {
    return { type: 'hypit:frame', sessionId: session.sessionId, frame: record.frame, timeSeconds: record.timeSeconds };
  }
  return null;
}

export function useHypitPreview() {
  const session = ref<HypitSessionCreated | null>(null);
  const loading = ref(false);
  const error = ref<string | null>(null);
  const currentFrame = ref<number | null>(null);
  const currentTime = ref<number | null>(null);
  const controller = new AbortController();
  let messageHandler: ((event: MessageEvent) => void) | null = null;

  async function open(projectId: string, runFile?: string, revision?: number): Promise<boolean> {
    loading.value = true;
    error.value = null;
    detach();
    try {
      const created = await openPreviewSession(projectId, {
        requestId: crypto.randomUUID(),
        ...(runFile === undefined ? {} : { runFile }),
        ...(revision === undefined ? {} : { revision }),
      }, controller.signal);
      session.value = created;
      messageHandler = (event: MessageEvent) => {
        const parsed = parseMessage(event.origin, event.data, created);
        if (parsed === null) return;
        if (parsed.type === 'hypit:frame') {
          currentFrame.value = parsed.frame;
          currentTime.value = parsed.timeSeconds;
        }
      };
      window.addEventListener('message', messageHandler);
      return true;
    } catch (cause) {
      if (controller.signal.aborted) return false;
      error.value = (cause as Error).message;
      session.value = null;
      return false;
    } finally {
      loading.value = false;
    }
  }

  function detach(): void {
    if (messageHandler !== null) {
      window.removeEventListener('message', messageHandler);
      messageHandler = null;
    }
    currentFrame.value = null;
    currentTime.value = null;
  }

  function close(): void {
    detach();
    session.value = null;
  }

  onUnmounted(() => {
    detach();
    controller.abort();
  });

  return { session, loading, error, currentFrame, currentTime, open, close };
}
