import { onScopeDispose, ref } from 'vue'
import type { Ref } from 'vue'
import { useCreationWorkspace } from '../../../lib/creation-workspace'
import type { CreationProject, CreationProjectCapability } from '../../../types/creation'

/**
 * 工作区自动保存（任务书 #92 C-04，D-03）：字段变更 800ms 防抖保存，离开页面前 flush 一次；
 * 保存失败显示可重试状态、不阻塞继续编辑；409 冲突按 §6.4 重新读取并以本地未提交字段为准合并。
 * 只做保存与恢复，不触发生成/扣费副作用（§5.4）。
 */
export type WorkspaceSaveState = 'idle' | 'saving' | 'saved' | 'error'

export interface WorkspaceAutosaveOptions {
  capability: CreationProjectCapability
  /** 步骤白名单（§5.1）：未知/缺失 currentStep 归一为首步。 */
  steps: readonly string[]
  currentStep: Ref<string>
  /** 表单字段 → workspace.inputs（只采结构化文本，D-04）。 */
  collectInputs: () => Record<string, unknown>
  /** workspace.inputs → 表单字段（恢复）。 */
  applyInputs: (inputs: Record<string, unknown>) => void
  /** 附加回填（标题等）。 */
  applyProject?: (project: CreationProject) => void
  /** §5.3 有效输入：trim 后至少一个能力必需字段非空；否则不创建 draft。 */
  isValidInput: () => boolean
  deriveTitle: () => string
  /** 结果资产 ID 列（C-05）：随保存载荷整列提交，服务端去重 ≤20；缺省不提交（PUT 保留现值）。 */
  collectResultAssetIds?: () => string[]
  /** 路由型视图的刷新恢复：`?draft=` 深链取 id。 */
  restoreRouteDraftId?: () => string | null
  /** 是否启用（共享视图只在 AI 应用挂载时启用，草场行为零变化）。 */
  engage: () => boolean
}

const AUTOSAVE_DEBOUNCE_MS = 800

export function useWorkspaceAutosave(options: WorkspaceAutosaveOptions) {
  const workspace = useCreationWorkspace()
  const draftId = ref('')
  const draftVersion = ref(0)
  const saveState = ref<WorkspaceSaveState>('idle')
  const conflictNotice = ref('')
  const restoredProjectId = ref('')
  let timer: ReturnType<typeof setTimeout> | null = null
  let saveEpoch = 0

  const engaged = options.engage()

  function clearTimer(): void {
    if (timer) {
      clearTimeout(timer)
      timer = null
    }
  }

  /** 采纳恢复的项目：版本/步骤（白名单归一）/字段/标题。 */
  function adopt(project: CreationProject): void {
    draftId.value = project.id
    draftVersion.value = project.version
    const payload = project.workspace ?? {}
    const step = typeof payload.currentStep === 'string' && options.steps.includes(payload.currentStep)
      ? payload.currentStep
      : options.steps[0]
    options.currentStep.value = step
    options.applyInputs(payload.inputs && typeof payload.inputs === 'object'
      ? payload.inputs as Record<string, unknown>
      : {})
    options.applyProject?.(project)
    restoredProjectId.value = project.id
    saveState.value = 'idle'
  }

  if (engaged) {
    // 恢复优先级：C-03 的 pendingContinue 交接 → ?draft= 深链（刷新恢复）
    const pending = workspace.pendingContinue.value
    if (pending && pending.capability === options.capability) {
      adopt(pending)
      workspace.setPendingContinue(null)
    } else {
      const routeDraftId = options.restoreRouteDraftId?.()
      if (routeDraftId) {
        void workspace.loadProject(routeDraftId).then((project) => {
          if (project && project.capability === options.capability) adopt(project)
        })
      }
    }
  }

  /** 记录改动并安排防抖保存；冲突态停止自动排程（先由用户合并/重试）。 */
  function queueSave(): void {
    if (!engaged || conflictNotice.value) return
    clearTimer()
    timer = setTimeout(() => { void flush() }, AUTOSAVE_DEBOUNCE_MS)
  }

  async function flush(): Promise<boolean> {
    clearTimer()
    if (!engaged) return true
    if (!draftId.value && !options.isValidInput()) return true
    const epoch = ++saveEpoch
    saveState.value = 'saving'
    const payload = {
      title: options.deriveTitle() || '未命名草稿',
      capability: options.capability,
      workspace: {
        capability: options.capability,
        currentStep: options.currentStep.value,
        inputs: options.collectInputs(),
      },
      ...(options.collectResultAssetIds ? { resultAssetIds: options.collectResultAssetIds() } : {}),
    }
    try {
      const saved = draftId.value
        ? await workspace.saveProject({ ...payload, id: draftId.value, expectedVersion: draftVersion.value })
        : await workspace.saveProject(payload)
      if (epoch !== saveEpoch) return false
      draftId.value = saved.id
      draftVersion.value = saved.version
      saveState.value = 'saved'
      return true
    } catch (err: unknown) {
      if (epoch !== saveEpoch) return false
      if ((err as { status?: number }).status === 409 && draftId.value) {
        // §6.4 / C-04：GET 最新版本，保留本地未提交字段（本地为准），用新 version 合并重放一次
        const fresh = await workspace.loadProject(draftId.value)
        if (fresh && epoch === saveEpoch) {
          draftVersion.value = fresh.version
          try {
            const merged = await workspace.saveProject({ ...payload, id: draftId.value, expectedVersion: fresh.version })
            if (epoch === saveEpoch) {
              draftVersion.value = merged.version
              saveState.value = 'saved'
              conflictNotice.value = ''
              return true
            }
          } catch (retryErr) {
            if (epoch !== saveEpoch) return false
            conflictNotice.value = (retryErr as { status?: number }).status === 409
              ? '草稿已在其他设备修改，已尝试合并仍未成功——点击重试再合并一次'
              : ''
          }
        }
      }
      saveState.value = 'error'
      return false
    }
  }

  /** 显式重试（「保存失败·重试」按钮）。 */
  async function retry(): Promise<void> {
    conflictNotice.value = ''
    await flush()
  }

  onScopeDispose(() => {
    // 卸载：取消排程并 flush 一次（§6.5：页面卸载前只同步 flush 一次）
    if (engaged && (draftId.value || options.isValidInput())) void flush()
    else clearTimer()
  })

  return { draftId, draftVersion, saveState, conflictNotice, restoredProjectId, queueSave, flush, retry }
}
