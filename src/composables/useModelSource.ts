import { ref } from 'vue'
import { AiControlPlaneError, useAiControlPlane } from './useAiControlPlane'
import { useAccountSessionStore, type AccountTicket } from '../stores/account-session'
import type { ModelSource } from '../types/ai-control-plane'

/**
 * 个人「模型来源」总开关共享态（任务书 #78 卡 C）。
 *
 * 模块级单例 ref：「AI 与治理」板块的开关卡与创作面的提示（检查步/手动复查在 own 态的
 * 文案变化）读同一份状态，切一次全应用感知。加载幂等（并发去重），切换后由调用方 reload。
 *
 * 任务书 #79 C79-02：与账号会话票据绑定——即使某入口忘了调 reset，load/setSource 的
 * 迟到结果也会因票据过期被丢弃（原局部 requestEpoch 与 409 重载逻辑原样保留）。
 */
const modelSource = ref<ModelSource>('platform')
const masterVersion = ref(0)
const loaded = ref(false)
const loading = ref(false)
const loadError = ref('')
let requestEpoch = 0
let pendingLoad: Promise<void> | null = null
let pendingTicket: AccountTicket | null = null

function reset(): void {
  requestEpoch += 1
  pendingLoad = null
  pendingTicket = null
  modelSource.value = 'platform'
  masterVersion.value = 0
  loaded.value = false
  loading.value = false
  loadError.value = ''
}

/** 局部代次 + 账号票据双重判定（D79-04）：任一失配即视为过期。 */
function isOutdated(epoch: number, ticket: AccountTicket): boolean {
  if (epoch !== requestEpoch) return true
  return !useAccountSessionStore().isCurrent(ticket)
}

export function useModelSource() {
  const api = useAiControlPlane()

  /**
   * 拉取当前总开关（幂等：**同 owner** 并发调用只发一次请求——E03。
   * 去重按票据归属：换账号后即使入口忘了 reset，B 的加载也不会被 A 的 pending 吞掉）。
   */
  function load(): Promise<void> {
    const session = useAccountSessionStore()
    const ticket = session.capture()
    if (pendingLoad && pendingTicket && session.isCurrent(pendingTicket)) return pendingLoad
    const epoch = requestEpoch
    loading.value = true
    loadError.value = ''
    const request = api.getModelSource().then((data) => {
      if (isOutdated(epoch, ticket)) return
      if (!data || !['platform', 'own'].includes(data.modelSource)
        || !Number.isInteger(data.masterVersion) || data.masterVersion < 0) {
        throw new Error('模型来源接口尚未就绪，请确认服务已更新后重试')
      }
      modelSource.value = data.modelSource
      masterVersion.value = data.masterVersion
      loaded.value = true
    }).catch((caught: unknown) => {
      if (isOutdated(epoch, ticket)) return
      loaded.value = false
      loadError.value = caught instanceof Error ? caught.message : '模型来源加载失败，请重试'
    }).finally(() => {
      // pendingLoad/pendingTicket 由发起者自己清理（票据过期不劫持后续 load 的去重）；
      // loading 只在未过期时释放：旧请求不清新请求的 loading。
      if (pendingLoad === request) {
        pendingLoad = null
        pendingTicket = null
      }
      if (!isOutdated(epoch, ticket)) loading.value = false
    })
    pendingLoad = request
    pendingTicket = ticket
    return request
  }

  /**
   * 切换总开关（乐观锁）。返回错误消息或 null（成功）。
   * 切 own 的二次确认由调用方（ModelSourceCard）负责——只有 UI 入口需要拦。
   */
  async function setSource(next: ModelSource): Promise<string | null> {
    if (!loaded.value || loading.value) return '请先成功加载模型来源，再重试切换'
    const epoch = requestEpoch
    const ticket = useAccountSessionStore().capture()
    try {
      const saved = await api.setModelSource({ modelSource: next, expectedVersion: masterVersion.value })
      if (isOutdated(epoch, ticket)) return '登录账号已变更，请重试'
      modelSource.value = saved.modelSource
      masterVersion.value = saved.masterVersion
      loaded.value = true
      return null
    } catch (caught: unknown) {
      if (isOutdated(epoch, ticket)) return '登录账号已变更，请重试'
      // 409：他人已改——重载真实状态让调用方提示重试（PersonalAiBudgetCard 同款口径）
      if (caught instanceof AiControlPlaneError && caught.status === 409) {
        await load()
        return loadError.value || '模型来源已被其他会话修改，请重试'
      }
      return caught instanceof Error ? caught.message : '模型来源切换失败'
    }
  }

  return { modelSource, masterVersion, loaded, loading, loadError, load, setSource, reset }
}
