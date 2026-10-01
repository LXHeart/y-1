/**
 * useHypitProjectScope.ts — C107F2-11（W090 / §4.4 / RULE-07）：
 * 账号 + 项目两级 generation 与 AbortController 的唯一提供者。切换项目/账号时
 * 递增 generation 并 abort 旧信号；域 composable 的 refresh 接收本 gate，旧请求
 * 的 success/error/finally 一律不得改新状态（isCurrent() 为 false 即整体丢弃）。
 */
import { getCurrentInstance, onUnmounted, ref } from 'vue';

export type ScopeGate = {
  /** 本世代的 AbortSignal：切换即 abort。 */
  signal: AbortSignal;
  /** 发起时捕获的世代号。 */
  generation: number;
  /** 回调落笔前判定：世代已过即丢弃（含 finally 的 loading 复位）。 */
  isCurrent: () => boolean;
};

/** 域 composable refresh 的可选隔离闸（W081/W086–W089 消费）。 */
export type RefreshGate = Pick<ScopeGate, 'signal' | 'isCurrent'>;

export function useHypitProjectScope() {
  const accountGeneration = ref(0);
  const projectGeneration = ref(0);
  const projectId = ref<string | null>(null);
  let accountController = new AbortController();
  let projectController = new AbortController();

  /** 账号切换（登出/换号）：账号世代 +1，账号与项目在途全部 abort。 */
  function switchAccount(): ScopeGate {
    accountController.abort();
    accountController = new AbortController();
    projectController.abort();
    projectController = new AbortController();
    accountGeneration.value += 1;
    const generation = accountGeneration.value;
    return { signal: projectController.signal, generation, isCurrent: () => generation === accountGeneration.value };
  }

  /** 项目切换：即使切回同一 id 也开新世代（A→B→A 的第三个 A 是新请求）。 */
  function switchProject(nextId: string | null): ScopeGate {
    projectController.abort();
    projectController = new AbortController();
    projectGeneration.value += 1;
    projectId.value = nextId;
    const generation = projectGeneration.value;
    return { signal: projectController.signal, generation, isCurrent: () => generation === projectGeneration.value };
  }

  function isCurrentProject(generation: number): boolean {
    return generation === projectGeneration.value;
  }

  function isCurrentAccount(generation: number): boolean {
    return generation === accountGeneration.value;
  }

  // 组件外调用（测试）由调用方持有引用；仅组件内自动随卸载 abort。
  if (getCurrentInstance()) {
    onUnmounted(() => {
      accountController.abort();
      projectController.abort();
    });
  }

  return {
    accountGeneration, projectGeneration, projectId,
    switchAccount, switchProject, isCurrentProject, isCurrentAccount,
  };
}
