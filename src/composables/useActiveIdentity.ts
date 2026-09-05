/**
 * 全局活动身份（PRD §一/§11.3）：商家 / 推荐官是同一账号可开通的两种身份，
 * 同一时间只启用一个**活动身份**；主导航、工作台视角与账号菜单共享同一状态。
 *
 * 服务端按 session 记录活动身份（`POST /api/me/active-identity`），本 composable
 * 持有它的客户端镜像——切换永远经后端校验，未开通身份的切换不得只改本地状态。
 *
 * 模块级单例：DefaultLayout（登录/换账号时装载）、GrasslandWorkbench（工作台初始化
 * 复用同一结果）与账号菜单读的都是同一组 ref。
 *
 * 2026-09-04 身份模型改版（任务书 #71 D8）：登录表单身份选择退役后，布局账号 watch
 * 的默认激活是唯一写入者——激活独占声明/竞态机制整体删除，天然无赛跑。
 *
 * 任务书 #79 C79-03：装载/激活/reset 的提交边界全部挂账号票据——旧账号的迟到响应
 * 不再写入身份表或活动侧（E09/E11）；初始激活与显式切换经同一串行队列执行，
 * 显式动作排在 bootstrap 之后、完成后不被默认覆盖（E15）。
 */
import { computed, ref } from 'vue'
import { useAccountSessionStore } from '../stores/account-session'
import type { useGrassland } from './useGrassland'
import type { IdentityProfile, StoreAccessScope } from '../types/grassland'

export type IdentitySide = 'merchant' | 'recommender'

const activeSide = ref<IdentitySide>('merchant')
const identities = ref<IdentityProfile[]>([])
/** 已完成一次身份装载（换账号/登出后置回 false）。 */
const identitiesLoaded = ref(false)
/** 无商家身份但持门店管理范围：商家视角本地生效（服务端不激活，门店范围自证权限）。 */
const merchantViewViaManagerScope = ref(false)
/** 初始视角激活每个账号只做一次（防并发装载用默认覆盖显式选择）。 */
let initialActivationApplied = false
/**
 * 服务端已激活侧的本地镜像（null = 服务端会话尚无活动身份）。
 * `activeSide` 只是 UI 视角猜测（默认 merchant），不能证明服务端已激活——
 * 冷会话选「商家」登录时若按 `activeSide === next` 短路激活 POST，会话会一直停在
 * 消费者（isMerchant()=false，草稿/资料全部不可见）。快路径必须以本镜像为准。
 */
let serverActivatedSide: IdentitySide | null = null

/**
 * 激活串行队列（任务书 #79 C79-03）：初始激活与显式切换共用一条链——显式动作排在
 * bootstrap 的默认激活之后，默认激活不得覆盖先/后完成的显式选择。
 */
let activationChain: Promise<unknown> = Promise.resolve()
/** 显式切换（activateIdentitySide）完成计数：装载期间的显式切换使默认激活失效。 */
let explicitActivationCount = 0

function enqueueActivation<T>(task: () => Promise<T>): Promise<T> {
  const next = activationChain.then(task, task)
  activationChain = next.catch(() => { /* 链条不因失败中断，错误由任务自身语义返回 */ })
  return next
}

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
   * - 有推荐官身份（即使同时持门店管理范围）→ 激活推荐官，管理范围不压身份档案；
   * - 无任何身份档案、仅有门店管理范围 → 商家视角本地生效，**不激活**；
   * - 仅推荐官 → 激活推荐官（沿用默认 merchant 会收到可预期 409）；
   * - 零档案且无门店范围、无组织归属 → 裸账号兜底：自动补开推荐官再装载（D6）。
   */
  async function loadAccountIdentity(grassland: ReturnType<typeof useGrassland>):
  Promise<AccountIdentitySnapshot | null> {
    const session = useAccountSessionStore()
    const ticket = session.capture()
    const activationBaseline = explicitActivationCount
    const [identityResult, scopeResult] = await Promise.all([
      grassland.listIdentities(),
      grassland.listMyStoreScopes(),
    ])
    if (!session.isCurrent(ticket)) return null
    if (identityResult === null) return null

    const storeScopes = Array.isArray(scopeResult) ? scopeResult : []
    let currentIdentities = identityResult
    // 裸账号兜底（D6，2026-09-04 身份模型改版）：仅存量裸账号（零档案+零门店范围+零
    // 组织归属）自动补开推荐官。有门店范围=店长/店员视角、有组织归属=主体子账号/池
    // 成员——他们都保持既有商家视角，不误开推荐官档案。至多兜底一次，不开环。
    if (currentIdentities.length === 0 && storeScopes.length === 0) {
      const organizations = await grassland.listOrganizations()
      if (!session.isCurrent(ticket)) return null
      if (Array.isArray(organizations) && organizations.length === 0) {
        const opened = await grassland.openIdentity('recommender')
        if (!session.isCurrent(ticket)) return null
        if (opened !== null) {
          grassland.clearError()
          const refreshed = await grassland.listIdentities()
          if (!session.isCurrent(ticket)) return null
          if (refreshed !== null) currentIdentities = refreshed
        }
      }
    }

    identities.value = currentIdentities
    identitiesLoaded.value = true
    const hasManagerScope = storeScopes.some((scope) => scope.role === 'manager')
    // 管理范围兜底只在**没有任何身份档案**时生效：有推荐官身份（即使同时持门店
    // 管理范围）应激活推荐官——管理范围是授权视图，不该压过真实身份档案。
    merchantViewViaManagerScope.value =
      !hasMerchantIdentity.value && !hasRecommenderIdentity.value && hasManagerScope

    const initialIdentity: IdentitySide | null = hasMerchantIdentity.value
      ? 'merchant'
      : hasRecommenderIdentity.value
        ? 'recommender'
        : null
    if (merchantViewViaManagerScope.value) activeSide.value = 'merchant'
    // 初始激活每个账号只做一次：并发装载（布局 watch 与工作台 init）后到的一方
    // 不得用「商家优先」默认覆盖已按档案/服务端会话激活的一侧。
    if (initialIdentity && !initialActivationApplied) {
      initialActivationApplied = true
      // 装载期间已有显式切换完成：本账号的激活决定已由显式动作做出，默认不再介入。
      if (explicitActivationCount !== activationBaseline) {
        return { identities: currentIdentities, storeScopes }
      }
      await enqueueActivation(async () => {
        if (!session.isCurrent(ticket)) return
        // 会话已激活过身份（登录时选定/上次激活，session 存活期内刷新页面仍在）→ 以
        // 服务器为准，不重激活——否则双身份账号选推荐官后，工作台装载/刷新会翻回商家。
        const serverActive = await grassland.getActiveIdentity()
        if (!session.isCurrent(ticket)) return
        const serverSide = serverActive?.activeIdentityType === 'merchant'
          || serverActive?.activeIdentityType === 'recommender'
          ? serverActive.activeIdentityType : null
        if (serverSide && (serverSide === 'merchant' ? hasMerchantIdentity.value : hasRecommenderIdentity.value)) {
          activeSide.value = serverSide
          serverActivatedSide = serverSide
        } else {
          activeSide.value = initialIdentity
          const activated = await grassland.activateIdentity(initialIdentity)
          if (!session.isCurrent(ticket)) return
          if (activated !== null) serverActivatedSide = initialIdentity
        }
        grassland.clearError() // 已知身份的激活失败由后续具体操作给出更明确的错误
      })
    }
    return { identities: currentIdentities, storeScopes }
  }

  /**
   * 账号菜单切换：仅允许在**已开通**身份之间切换（未开通的身份在工作台内引导开通，
   * 不从菜单暗中开户）。激活成功才落本地镜像；失败保持原视角，错误留在 grassland.error。
   */
  async function activateIdentitySide(
    next: IdentitySide, grassland: ReturnType<typeof useGrassland>,
  ): Promise<ActivateIdentityResult> {
    const session = useAccountSessionStore()
    const ticket = session.capture()
    return enqueueActivation(async () => {
      // 快路径只在服务端确认已处于该侧时成立（activeSide 是 UI 猜测，默认即 merchant——
      // 冷会话登录选商家若据此短路，服务端会话将停留在消费者，商家数据全部不可见）。
      if (next === activeSide.value && serverActivatedSide === next) return 'ok'
      // 旧票据不激活（E09）：换号后排队到达的显式切换不得对 B 会话发 A 动作
      if (!session.isCurrent(ticket)) return 'failed'
      const opened = next === 'merchant' ? hasMerchantIdentity.value : hasRecommenderIdentity.value
      if (!opened) return 'not-opened'
      const activated = await grassland.activateIdentity(next)
      if (!session.isCurrent(ticket)) return 'failed'
      if (activated === null) return 'failed'
      explicitActivationCount += 1
      grassland.clearError()
      activeSide.value = next
      serverActivatedSide = next
      return 'ok'
    })
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
    initialActivationApplied = false
    serverActivatedSide = null // 换账号 = 新会话，服务端激活状态未知
  }

  return {
    activeSide, identities, identitiesLoaded, merchantViewViaManagerScope,
    hasMerchantIdentity, hasRecommenderIdentity,
    loadAccountIdentity, activateIdentitySide, reset,
  }
}
