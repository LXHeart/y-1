/**
 * useHypitPreview.ts — C107-21 (task-107) 首建；107-fix-2 C107F2-23 按 §6.10 重写：
 *
 * - sandbox=allow-scripts 的 opaque origin（event.origin=null）是正常值——绝不做
 *   host 子串校验；身份三关是 event.source===绑定的 contentWindow、sessionId、
 *   会话 messageNonce，再过严格 schema（ready/frame/error）。
 * - frame 非负安全整数、timeSeconds 有限非负；unknown 消息静默忽略。
 * - 关闭/切工程/卸载：幂等 close（DELETE）、detach 监听、清时钟、作废在途 open
 *   （generation 代际）；网络失败不影响服务端 TTL 最终清理。
 * - 播放时钟只来自预览实际 frame 消息（不前端猜进度）。
 */
import { computed, onUnmounted, ref } from 'vue';
import { closePreviewSession, openPreviewSession } from './hypit-api';
import type { HypitSessionCreated } from '../../../types/hypit';

export type PreviewFrameMessage =
  | { readonly type: 'ready'; readonly sessionId: string }
  | { readonly type: 'frame'; readonly sessionId: string; readonly frame: number; readonly timeSeconds: number }
  | { readonly type: 'error'; readonly sessionId: string; readonly message: string };

export type PreviewControl =
  | { readonly kind: 'play'; readonly frame?: number }
  | { readonly kind: 'pause' }
  | { readonly kind: 'seek'; readonly frame: number }
  | { readonly kind: 'step'; readonly frames: number };

function isNonNegativeSafeInteger(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0;
}

function isNonNegativeFinite(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value) && value >= 0;
}

/** §6.10 严格 schema：三关（source 由 pump 先比对）+ 字段类型；unknown 一律 null。 */
function parseMessage(
  data: unknown,
  sessionId: string,
  nonce: string,
  source: Window | null,
): PreviewFrameMessage | null {
  if (source === null) return null;
  if (data === null || typeof data !== 'object') return null;
  const record = data as Record<string, unknown>;
  if (record.type !== 'ready' && record.type !== 'frame' && record.type !== 'error') return null;
  if (record.sessionId !== sessionId || record.messageNonce !== nonce) return null;
  if (record.type === 'ready') return { type: 'ready', sessionId };
  if (record.type === 'frame'
    && isNonNegativeSafeInteger(record.frame)
    && isNonNegativeFinite(record.timeSeconds)) {
    return { type: 'frame', sessionId, frame: record.frame, timeSeconds: record.timeSeconds };
  }
  if (record.type === 'error' && typeof record.message === 'string') {
    return { type: 'error', sessionId, message: record.message.slice(0, 300) };
  }
  return null;
}

export function useHypitPreview() {
  const session = ref<HypitSessionCreated | null>(null);
  const loading = ref(false);
  const error = ref<string | null>(null);
  const currentFrame = ref<number | null>(null);
  const currentTime = ref<number | null>(null);
  /** 本会话消息 nonce（open 时生成；iframe 文档经 broker 注入同一 nonce）。 */
  let messageNonce = '';
  /** 绑定的 iframe contentWindow——消息身份第一关（§6.10 event.source 校验）。 */
  let expectedSource: Window | null = null;
  let generation = 0;
  let projectId: string | null = null;
  const controller = new AbortController();
  let messageHandler: ((event: MessageEvent) => void) | null = null;

  function handleMessage(event: MessageEvent): void {
    // event.origin 对 sandbox=allow-scripts 的 iframe 是 null（opaque）——按 §6.10
    // 不做 origin/host 校验；身份靠 source + sessionId + nonce + schema。
    if (messageHandler === null || expectedSource === null) return;
    if (event.source !== expectedSource) return;
    const current = session.value;
    if (current === null) return;
    const parsed = parseMessage(event.data, current.sessionId, messageNonce, expectedSource);
    if (parsed === null) return;
    if (parsed.type === 'frame') {
      currentFrame.value = parsed.frame;
      currentTime.value = parsed.timeSeconds;
    } else if (parsed.type === 'error') {
      error.value = parsed.message;
    }
  }

  /** PreviewPanel 挂载/卸载 iframe 时绑定 contentWindow（null=解绑）。 */
  function bindFrame(source: Window | null): void {
    expectedSource = source;
  }

  /** 父→iframe 控制消息：显式目标窗口 + 会话 nonce；消息体不含任何秘密。 */
  function sendControl(control: PreviewControl): boolean {
    if (expectedSource === null || session.value === null) return false;
    const base: Record<string, unknown> = { type: 'control', messageNonce };
    let payload: Record<string, unknown>;
    switch (control.kind) {
      case 'play':
        payload = control.frame === undefined ? { ...base, kind: 'play' } : { ...base, kind: 'play', frame: control.frame };
        break;
      case 'pause':
        payload = { ...base, kind: 'pause' };
        break;
      case 'seek':
        payload = { ...base, kind: 'seek', frame: control.frame };
        break;
      case 'step':
        payload = { ...base, kind: 'step', frames: control.frames };
        break;
    }
    // §6.10：opaque origin 下 targetOrigin 必须为 "*"——payload 无秘密/身份令牌。
    expectedSource.postMessage(payload, '*');
    return true;
  }

  function detach(): void {
    if (messageHandler !== null) {
      window.removeEventListener('message', messageHandler);
      messageHandler = null;
    }
    expectedSource = null;
    currentFrame.value = null;
    currentTime.value = null;
  }

  async function open(project: string, runFile?: string, revision?: number): Promise<boolean> {
    const mine = ++generation;
    projectId = project;
    loading.value = true;
    error.value = null;
    detach();
    try {
      const created = await openPreviewSession(project, {
        requestId: crypto.randomUUID(),
        ...(runFile === undefined ? {} : { runFile }),
        ...(revision === undefined ? {} : { revision }),
      }, controller.signal);
      if (mine !== generation) return false; // 迟到回执：已被关闭/切工程作废，不重开。
      session.value = created;
      messageNonce = crypto.randomUUID();
      if (messageHandler === null) {
        messageHandler = handleMessage;
        window.addEventListener('message', messageHandler);
      }
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

  /** 关闭：幂等 DELETE + 本地清场；服务端失败不阻塞（TTL 兜底最终清理）。 */
  async function close(): Promise<void> {
    const stale = session.value;
    const owner = projectId;
    generation += 1; // 在途 open 回执作废（E04：挂起后关闭不重开）。
    detach();
    session.value = null;
    error.value = null;
    if (stale === null || owner === null) return;
    try {
      await closePreviewSession(owner, stale.sessionId);
    } catch {
      // 网络失败不重试轰炸：服务端 TTL 会最终回收（§6.10）。
    }
  }

  /** 切工程：同 close（服务端尽力通知 + 本地代际作废）。 */
  function reset(): void {
    void close();
  }

  onUnmounted(() => {
    detach();
    controller.abort();
  });

  return {
    session, loading, error, currentFrame, currentTime,
    /** 本会话消息 nonce（父页持有；iframe 文档经 broker 注入同一值）。 */
    nonce: computed(() => messageNonce),
    open, close, reset, bindFrame, sendControl,
  };
}
