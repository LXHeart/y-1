/**
 * 全局活动身份（PRD §一/§11.3）：商家 / 推荐官是同一账号可开通的两种身份，
 * 同一时间只启用一个**活动身份**；主导航、工作台视角与账号菜单共享同一状态。
 *
 * 服务端按 session 记录活动身份（`POST /api/me/active-identity`），本 composable
 * 持有它的客户端镜像——切换永远经后端校验，未开通身份的切换不得只改本地状态。
 *
 * 模块级单例：DefaultLayout（登录/换账号时装载）、GrasslandWorkbench（工作台初始化
 * 复用同一结果）与账号菜单读的都是同一组 ref。
 */
import { computed, ref } from 'vue'
import type { useGrassland } from './useGrassland'
import type { IdentityProfile, StoreAccessScope } from '../types/grassland'

export type IdentitySide = 'merchant' | 'recommender'

const activeSide = ref<IdentitySide>('merchant')
const identities = ref<IdentityProfile[]>([])
/** 已完成一次身份装载（换账号/登出后置回 false）。 */
const identitiesLoaded = ref(false)
/** 无商家身份但持门店管理范围：商家视角本地生效（服务端不激活，门店范围自证权限）。 */
const merchantViewViaManagerScope = ref(false)

const hasMerchantIdentity = computed(() =>
  identities.value.some((identity) => identity.identityType === 'merchant'))
const hasRecommenderIdentity = computed(() =>
  identities.value.some((identity) => identity.identityType === 'recommender'))

export interface AccountIdentitySnapshot {
  identities: IdentityProfile[]
  storeScopes: StoreAccessScope[]
}

/** 账号菜单切换结果：区分「未开通」（不可从菜单开通）与「后端切换失败」。 */
export type ActivateIdentityResult = 'ok' | 'not-opened' | 'failed'

export function useActiveIdentity() {
  /**
   * 装载账号身份并激活初始视角（原工作台 initForAccount 的身份段，原样上提）：
   * - 商家身份优先（双身份账号的既有默认）；
   * - 无商家身份但有门店管理范围 → 商家视角本地生效，**不激活**；
   * - 仅推荐官 → 激活推荐官（沿用默认 merchant 会收到可预期 409）；
   * - 无任何身份 → 保持 merchant 视角进入入驻引导，不暗中开户。
   */
  async function loadAccountIdentity(grassland: ReturnType<typeof useGrassland>):
  Promise<AccountIdentitySnapshot | null> {
    const [identityResult, scopeResult] = await Promise.all([
      grassland.listIdentities(),
      grassland.listMyStoreScopes(),
    ])
    if (identityResult === null) return null

    const storeScopes = Array.isArray(scopeResult) ? scopeResult : []
    identities.value = identityResult
    identitiesLoaded.value = true
    merchantViewViaManagerScope.value =
      !hasMerchantIdentity.value && storeScopes.some((scope) => scope.role === 'manager')

    const initialIdentity: IdentitySide | null = hasMerchantIdentity.value
      ? 'merchant'
      : merchantViewViaManagerScope.value
        ? null
        : hasRecommenderIdentity.value
          ? 'recommender'
          : null
    if (merchantViewViaManagerScope.value) activeSide.value = 'merchant'
    if (initialIdentity) {
      activeSide.value = initialIdentity
      await grassland.activateIdentity(initialIdentity)
      grassland.clearError() // 已知身份的激活失败由后续具体操作给出更明确的错误
    }
    return { identities: identityResult, storeScopes }
  }

  /**
   * 账号菜单切换：仅允许在**已开通**身份之间切换（未开通的身份在工作台内引导开通，
   * 不从菜单暗中开户）。激活成功才落本地镜像；失败保持原视角，错误留在 grassland.error。
   */
  async function activateIdentitySide(
    next: IdentitySide, grassland: ReturnType<typeof useGrassland>,
  ): Promise<ActivateIdentityResult> {
    if (next === activeSide.value) return 'ok'
    const opened = next === 'merchant' ? hasMerchantIdentity.value : hasRecommenderIdentity.value
    if (!opened) return 'not-opened'
    const activated = await grassland.activateIdentity(next)
    if (activated === null) return 'failed'
    grassland.clearError()
    activeSide.value = next
    return 'ok'
  }

  /**
   * 换账号/登出：清空身份表与标记，下一次装载重新计算。
   * `activeSide` 刻意不清（与原工作台 resetAccountState 的「side/wallet 刻意不清」一致）——
   * 新账号装载时会按其身份重新计算；清了反而会在换账号瞬间触发一次无意义的视角翻转。
   */
  function reset(): void {
    identities.value = []
    identitiesLoaded.value = false
    merchantViewViaManagerScope.value = false
  }

  return {
    activeSide, identities, identitiesLoaded, merchantViewViaManagerScope,
    hasMerchantIdentity, hasRecommenderIdentity,
    loadAccountIdentity, activateIdentitySide, reset,
  }
}
