// @vitest-environment happy-dom
// fix2-c23.test.ts — C107F2-23（TC-F2-23-01～04，§6.10）：预览跨帧消息、播放状态与
// 资源释放。composable 层：mock HTTP 跑真实 useHypitPreview + 真实 PreviewPanel 挂载。
// happy-dom 的 MessageEvent.source/iframe.contentWindow 均为 null——消息泵以捕获的
// window message 监听器直呼合成事件（source 字段自造）验证三关身份
// （event.source/sessionId/nonce+schema）；面板只验 src/时钟显示。
import { afterEach, describe, expect, test, vi } from 'vitest';
import { effectScope } from 'vue';
import { mount } from '@vue/test-utils';
import PreviewPanel from '../components/PreviewPanel.vue';
import { useHypitPreview } from './useHypitPreview';

const PROJECT = '44444444-4444-4444-8444-444444444444';

function respond(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });
}

const SESSION = {
  sessionId: 'pv-c23-0000000f',
  ticketUrl: '/preview/pv-c23-0000000f/',
  expiresAt: new Date(Date.now() + 600_000).toISOString(),
  revision: 2,
  missingMaterials: [],
};

function installPreviewFetch(): { posts: string[]; deletes: string[]; gate: { release: (() => void) | null } } {
  const posts: string[] = [];
  const deletes: string[] = [];
  const gate: { release: (() => void) | null } = { release: null };
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    if (init?.method === 'DELETE') {
      deletes.push(url);
      return respond({ success: true, data: { closed: true } });
    }
    posts.push(url);
    if (gate.release !== null) {
      const release = gate.release;
      gate.release = null;
      await release();
    }
    return respond({ success: true, data: SESSION });
  }));
  return { posts, deletes, gate };
}

type MessagePump = (data: unknown, source: unknown, origin?: string) => void;

/** 捕获 composable 注册的 window 'message' 监听器（happy-dom 上直呼合成事件）。 */
function captureMessageListener(): MessagePump {
  let captured: ((event: { source: unknown; data: unknown; origin: string }) => void) | null = null;
  const original = window.addEventListener.bind(window);
  vi.spyOn(window, 'addEventListener').mockImplementation(((type: string, listener: EventListenerOrEventListenerObject) => {
    if (type === 'message') {
      captured = listener as unknown as (event: { source: unknown; data: unknown; origin: string }) => void;
    }
    return original(type, listener as EventListener);
  }) as typeof window.addEventListener);
  return (data: unknown, source: unknown, origin = ''): void => {
    captured?.({ data, source, origin });
  };
}

afterEach(() => vi.unstubAllGlobals());

describe('C107F2-23 useHypitPreview 跨帧消息', () => {
  test('TC-F2-23-01 sandbox iframe、正确 source/session/nonce、origin=null（opaque）：接收 frame0/time0 显示 0', async () => {
    installPreviewFetch();
    const scope = effectScope();
    const preview = scope.run(() => useHypitPreview())!;
    const pump = captureMessageListener();
    expect(await preview.open(PROJECT, 'main.svrun', 2)).toBe(true);
    expect(preview.session.value?.sessionId).toBe(SESSION.sessionId);
    const nonce = preview.nonce.value;
    expect(nonce).not.toBe('');

    const frameWindow = { postMessage: vi.fn() } as unknown as Window;
    preview.bindFrame(frameWindow);
    // origin=''（sandbox=allow-scripts 的 opaque origin=null 表现）——不因 origin 拒绝。
    pump({ type: 'frame', sessionId: SESSION.sessionId, messageNonce: nonce, frame: 0, timeSeconds: 0 },
      frameWindow, '');
    expect(preview.currentFrame.value).toBe(0);
    expect(preview.currentTime.value).toBe(0);

    // 面板挂载：src 来自 ticketUrl、时钟显示「帧 0」。
    const panel = mount(PreviewPanel, {
      props: {
        session: preview.session.value,
        loading: false,
        error: null,
        currentFrame: preview.currentFrame.value,
        currentTime: preview.currentTime.value,
      },
    });
    expect(panel.find('iframe').attributes('src')).toBe(SESSION.ticketUrl);
    expect(panel.get('[data-testid="clone-preview-clock"]').text()).toContain('帧 0');
    panel.unmount();
    preview.bindFrame(null);
  });

  test('TC-F2-23-02 同域另一 iframe 正确 session 但错误 source：忽略且不改播放状态', async () => {
    installPreviewFetch();
    const scope = effectScope();
    const preview = scope.run(() => useHypitPreview())!;
    const pump = captureMessageListener();
    await preview.open(PROJECT, 'main.svrun', 2);
    const nonce = preview.nonce.value;
    const realWindow = { postMessage: vi.fn() } as unknown as Window;
    const otherWindow = { postMessage: vi.fn() } as unknown as Window;
    preview.bindFrame(realWindow);

    const good = { type: 'frame', sessionId: SESSION.sessionId, messageNonce: nonce, frame: 5, timeSeconds: 0.2 };
    pump(good, otherWindow);
    expect(preview.currentFrame.value).toBeNull();
    pump(good, realWindow);
    expect(preview.currentFrame.value).toBe(5);
    preview.bindFrame(null);
  });

  test('TC-F2-23-03 正确 source 发 NaN/负数/未知 type：全部忽略且无异常', async () => {
    installPreviewFetch();
    const scope = effectScope();
    const preview = scope.run(() => useHypitPreview())!;
    const pump = captureMessageListener();
    await preview.open(PROJECT, 'main.svrun', 2);
    const nonce = preview.nonce.value;
    const frameWindow = { postMessage: vi.fn() } as unknown as Window;
    preview.bindFrame(frameWindow);
    const base = { sessionId: SESSION.sessionId, messageNonce: nonce };
    pump({ type: 'frame', ...base, frame: Number.NaN, timeSeconds: 0 }, frameWindow);
    pump({ type: 'frame', ...base, frame: -1, timeSeconds: 0 }, frameWindow);
    pump({ type: 'frame', ...base, frame: 1.5, timeSeconds: 0 }, frameWindow);
    pump({ type: 'frame', ...base, frame: 3, timeSeconds: Number.NEGATIVE_INFINITY }, frameWindow);
    pump({ type: 'greeting', ...base }, frameWindow);
    pump({ type: 'frame', sessionId: 'pv-other', messageNonce: nonce, frame: 9, timeSeconds: 1 }, frameWindow);
    expect(preview.currentFrame.value).toBeNull();
    expect(preview.currentTime.value).toBeNull();
    expect(preview.error.value).toBeNull();
    preview.bindFrame(null);
  });

  test('TC-F2-23-04 open 挂起后关闭：释放旧响应不重开；close 幂等可重试；reset 服务端 DELETE 恰一次', async () => {
    const { deletes } = installPreviewFetch();
    const scope = effectScope();
    const preview = scope.run(() => useHypitPreview())!;
    // 第一次 open 挂起（fetch 已在 gate 上等待）。
    const holder: { release?: () => void } = {};
    const blocked = new Promise<void>((resolveBlock) => { holder.release = resolveBlock; });
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (init?.method === 'DELETE') {
        deletes.push(url);
        return respond({ success: true, data: { closed: true } });
      }
      await blocked;
      return respond({ success: true, data: SESSION });
    }));
    const opening = preview.open(PROJECT, 'main.svrun', 2);
    // 挂起中关闭：代际作废 + 本地清场（此时无 session，无服务端 DELETE）。
    const closing = preview.close();
    holder.release?.();
    expect(await opening).toBe(false);
    await closing;
    expect(preview.session.value).toBeNull();
    expect(deletes).toHaveLength(0);

    // close 幂等可重试：再次 open 成功。
    expect(await preview.open(PROJECT, 'main.svrun', 2)).toBe(true);
    expect(preview.session.value?.sessionId).toBe(SESSION.sessionId);
    // 切工程 reset：本地清场 + 服务端 DELETE 恰一次。
    await preview.reset();
    expect(preview.session.value).toBeNull();
    expect(deletes.filter((url) => url.includes(SESSION.sessionId))).toHaveLength(1);
    scope.stop();
  });
});
