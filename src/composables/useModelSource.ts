import { ref } from 'vue'
import { AiControlPlaneError, useAiControlPlane } from './useAiControlPlane'
import type { ModelSource } from '../types/ai-control-plane'

/**
 * 个人「模型来源」总开关共享态（任务书 #78 卡 C）。
 *
 * 模块级单例 ref：「AI 与治理」板块的开关卡与创作面的提示（检查步/手动复查在 own 态的
 * 文案变化）读同一份状态，切一次全应用感知。加载幂等（并发去重），切换后由调用方 reload。
 */
const modelSource = ref<ModelSource>('platform')
const masterVersion = ref(0)
const loaded = ref(false)
const loading = ref(false)
const loadError = ref('')
let requestEpoch = 0
let pendingLoad: Promise<void> | null = null

function reset(): void {
  requestEpoch += 1
  pendingLoad = null
  modelSource.value = 'platform'
  masterVersion.value = 0
  loaded.value = false
  loading.value = false
  loadError.value = ''
}

export function useModelSource() {
  const api = useAiControlPlane()

  /** 拉取当前总开关（幂等：并发调用只发一次请求）。 */
  function load(): Promise<void> {
    if (pendingLoad) return pendingLoad
    const epoch = requestEpoch
    loading.value = true
    loadError.value = ''
    const request = api.getModelSource().then((data) => {
      if (epoch !== requestEpoch) return
      if (!data || !['platform', 'own'].includes(data.modelSource)
        || !Number.isInteger(data.masterVersion) || data.masterVersion < 0) {
        throw new Error('模型来源接口尚未就绪，请确认服务已更新后重试')
      }
      modelSource.value = data.modelSource
      masterVersion.value = data.masterVersion
      loaded.value = true
    }).catch((caught: unknown) => {
      if (epoch !== requestEpoch) return
      loaded.value = false
      loadError.value = caught instanceof Error ? caught.message : '模型来源加载失败，请重试'
    }).finally(() => {
      if (epoch !== requestEpoch) return
      loading.value = false
      pendingLoad = null
    })
    pendingLoad = request
    return request
  }

  /**
   * 切换总开关（乐观锁）。返回错误消息或 null（成功）。
   * 切 own 的二次确认由调用方（ModelSourceCard）负责——只有 UI 入口需要拦。
   */
  async function setSource(next: ModelSource): Promise<string | null> {
    if (!loaded.value || loading.value) return '请先成功加载模型来源，再重试切换'
    const epoch = requestEpoch
    try {
      const saved = await api.setModelSource({ modelSource: next, expectedVersion: masterVersion.value })
      if (epoch !== requestEpoch) return '登录账号已变更，请重试'
      modelSource.value = saved.modelSource
      masterVersion.value = saved.masterVersion
      loaded.value = true
      return null
    } catch (caught: unknown) {
      if (epoch !== requestEpoch) return '登录账号已变更，请重试'
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
