/**
 * useHypitRuntime.ts — C107F2-09（W074 固定职责）：工作区顶层运行时状态。
 *
 * mount 先解析 capabilities：disabled/unavailable 时不发 projects 请求（闸门
 * 装在 hypit-api 层）；登录过期复用既有登录入口（视图 emit request-login）；
 * 恢复重试后只补拉一次列表（onReady 回调由视图接线）。视图只装配不写探测。
 *
 * 账号世代不依赖 pinia（既有 workbench 测试无 pinia 挂载必须保持绿）：
 * 以状态转移推导——每次从非 ready（含 401 会话失效）回到 ready 记为新世代，
 * 世代切换时调用 onAccountReset 清旧账号私有状态。
 */
import { onScopeDispose, readonly, ref, watch } from 'vue';
import { getCapabilities, setHypitRuntimeGate, type HypitRuntimeGateState } from './hypit-api';
import type { HypitCapabilities } from '../../../types/hypit';

export type HypitRuntimeStatus = 'checking' | 'ready' | 'disabled' | 'unauthenticated' | 'unavailable';

export interface UseHypitRuntimeOptions {
  /** 恢复/首就绪后由视图补拉一次列表（TC-F2-09-04：恢复后只拉一次）。 */
  onReady?: () => void;
  /** 会话失效或世代切换时清旧账号私有状态（TC-F2-09-03：旧账号数据清空）。 */
  onAccountReset?: () => void;
}

export function useHypitRuntime(options: UseHypitRuntimeOptions = {}) {
  const status = ref<HypitRuntimeStatus>('checking');
  const reason = ref<string | null>(null);
  const capabilities = ref<HypitCapabilities | null>(null);
  const probing = ref(false);
  /** 账号世代：会话失效→重新就绪、账号切换后重探都会递增。 */
  const generation = ref(0);

  let probeSequence = 0;
  let firstResult: Promise<HypitRuntimeGateState> | null = null;
  let releaseFirst: ((state: HypitRuntimeGateState) => void) | null = null;
  /** 初始自动拉取被闸门挡下后置位；恢复时 onReady 只补拉一次。 */
  let recoveredFetchPending = false;

  async function probe(): Promise<HypitRuntimeStatus> {
    const sequence = ++probeSequence;
    probing.value = true;
    status.value = 'checking';
    reason.value = null;
    try {
      const caps = await getCapabilities();
      if (sequence !== probeSequence) return status.value;
      capabilities.value = caps;
      if (caps.enabled !== true) {
        status.value = 'disabled';
        reason.value = disabledReason(caps);
      } else {
        status.value = 'ready';
      }
    } catch (cause) {
      if (sequence !== probeSequence) return status.value;
      capabilities.value = null;
      const err = cause as { status?: number; message?: string };
      if (err.status === 401) {
        status.value = 'unauthenticated';
        reason.value = '登录已过期，请重新登录。';
      } else {
        status.value = 'unavailable';
        reason.value = err.message ?? '视频复刻服务暂时不可达。';
      }
    } finally {
      if (sequence === probeSequence) probing.value = false;
    }
    return status.value;
  }

  /** disabled 的具体原因：优先取 feature.reason（普通 owner 只见脱敏文案）。 */
  function disabledReason(caps: HypitCapabilities): string {
    const feature = caps.features.find((item) => item.reason !== null && item.reason.length > 0);
    return feature?.reason ?? 'HYPIT_ENABLED=false：视频复刻服务未启用。';
  }

  /** 用户点击重试：重新探测；恢复后由 watch 触发一次 onReady。 */
  async function retry(): Promise<void> {
    await probe();
  }

  // 闸门：等待首次探测结果，此后按当前状态放行/拒绝。
  setHypitRuntimeGate(() => {
    if (firstResult === null) {
      firstResult = new Promise<HypitRuntimeGateState>((resolve) => {
        releaseFirst = resolve;
      });
      void probe().then(() => {
        const state: HypitRuntimeGateState = status.value === 'ready' ? 'ready' : 'blocked';
        if (status.value !== 'ready') recoveredFetchPending = true;
        const release = releaseFirst;
        releaseFirst = null;
        if (release !== null) release(state);
      });
    }
    return firstResult.then(() => (status.value === 'ready' ? 'ready' : 'blocked'));
  });

  watch(status, (next, prev) => {
    if (next === 'unauthenticated') {
      options.onAccountReset?.();
      return;
    }
    if (next === 'ready' && prev !== 'ready') {
      // 会话失效/不可用之后恢复 = 新账号世代（未重挂载也能隔离旧状态）。
      if (prev === 'unauthenticated' || prev === 'unavailable') generation.value += 1;
      if (recoveredFetchPending) {
        recoveredFetchPending = false;
        options.onReady?.();
      }
    }
  });

  // onScopeDispose：组件 setup 与独立 effectScope（测试）都能清理闸门，不遗留全局拦截。
  onScopeDispose(() => setHypitRuntimeGate(null));

  return {
    status: readonly(status),
    reason: readonly(reason),
    capabilities: readonly(capabilities),
    probing: readonly(probing),
    generation: readonly(generation),
    retry,
    probe,
  };
}
