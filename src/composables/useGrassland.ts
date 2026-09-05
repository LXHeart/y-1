import { ref } from 'vue'
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
  const loading = ref(false)
  const error = ref('')

  function clearError(): void {
    error.value = ''
  }

  /** 包装：统一 loading / error 处理，失败返回 null（调用方按 null 判定，不需 try-catch）。 */
  async function run<T>(operation: () => Promise<T>): Promise<T | null> {
    loading.value = true
    error.value = ''
    try {
      return await operation()
    } catch (caught: unknown) {
      // 402（积分不足/超预算）改写为充值引导：后端原始文案（「积分不足」「exceeds_*_budget」）
      // 对草场用户已无行动意义——充值入口只在 AI 创作中心。
      if (caught instanceof GrasslandHttpError && caught.status === 402) {
        error.value = CREDITS_402_MESSAGE
      } else {
        error.value = caught instanceof Error ? caught.message : '未知错误'
      }
      return null
    } finally {
      loading.value = false
    }
  }

  const identity = useGrasslandIdentity(run)
  const marketplace = useGrasslandMarketplace(run)
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
