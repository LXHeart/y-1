import { computed, ref } from 'vue'
import { getActivePinia } from 'pinia'
import { useAccountSessionStore } from '../stores/account-session'
import { GrasslandHttpError } from './grassland-http'
import { useGrasslandIdentity } from './useGrasslandIdentity'
import { useGrasslandMarketplace } from './useGrasslandMarketplace'
import { useGrasslandGovernance } from './useGrasslandGovernance'
import { useGrasslandAudit } from './useGrasslandAudit'

/**
 * 草场 Java 域请求封装（经 edge-bff）。
 *
 * 与旧 Express composable 的差异：
 * - 资金型 accept / confirm 返回 **202**，真实结果需轮询（{@link pollReservation} / {@link pollSettlement}）。
 * - 身份靠 cookie session → edge-bff 换发内部断言，故所有请求须 `credentials: 'include'`。
 * - 后端错误信封为 `{success:false, error}`，与 legacy 一致。
 *
 * 按功能域拆分为三个子 composable：
 * - {@link useGrasslandIdentity} — 组织、身份、权限、成员/门店、会话、邀请
 * - {@link useGrasslandMarketplace} — 交付物、media、画像/声誉、钱包、任务/报名、资金账户
 * - {@link useGrasslandGovernance} — 争议/审判、运营处置台、KYB、素材库、管理审核、财务对账
 */

/** 402 统一改写文案（任务书 #78 卡 A）：草场全域已无积分入口，一律引导去 AI 创作中心充值。 */
export const CREDITS_402_MESSAGE = '积分不足，请前往 AI 创作中心充值'

export function useGrassland() {
  const session = getActivePinia() ? useAccountSessionStore() : null
  const pending = ref({ epoch: session?.epoch ?? 0, count: 0 })
  const failure = ref({ epoch: session?.epoch ?? 0, message: '' })
  const loading = computed(() => pending.value.epoch === (session?.epoch ?? 0) && pending.value.count > 0)
  const error = computed({
    get: () => failure.value.epoch === (session?.epoch ?? 0) ? failure.value.message : '',
    set: (message: string) => { failure.value = { epoch: session?.epoch ?? 0, message } },
  })

  function clearError(): void {
    failure.value = { epoch: session?.epoch ?? 0, message: '' }
  }

  /** 包装：统一 loading / error 处理，失败返回 null（调用方按 null 判定，不需 try-catch）。 */
  async function run<T>(operation: () => Promise<T>): Promise<T | null> {
    const ticket = session?.capture()
    const epoch = ticket?.epoch ?? 0
    const current = () => !ticket || session!.isCurrent(ticket)
    pending.value = { epoch, count: pending.value.epoch === epoch ? pending.value.count + 1 : 1 }
    clearError()
    try {
      const result = await operation()
      return current() ? result : null
    } catch (caught: unknown) {
      if (!current()) return null
      // 402（积分不足/超预算）改写为充值引导：后端原始文案（「积分不足」「exceeds_*_budget」）
      // 对草场用户已无行动意义——充值入口只在 AI 创作中心。
      if (caught instanceof GrasslandHttpError && caught.status === 402) {
        failure.value = { epoch, message: CREDITS_402_MESSAGE }
      } else {
        failure.value = { epoch, message: caught instanceof Error ? caught.message : '未知错误' }
      }
      return null
    } finally {
      if (current()) pending.value.count -= 1
    }
  }

  const identity = useGrasslandIdentity(run)
  const marketplace = useGrasslandMarketplace(run, session)
  const governance = useGrasslandGovernance(run)
  const audit = useGrasslandAudit(run)

  return {
    loading,
    error,
    clearError,
    ...identity,
    ...marketplace,
    ...governance,
    ...audit,
  }
}
