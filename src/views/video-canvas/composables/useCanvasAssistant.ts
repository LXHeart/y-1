import { computed, ref, watch, type ComputedRef, type Ref } from 'vue'
import type { GraphNode } from './useCanvasGraph'
import type { CanvasStoryboard } from '../useVideoCanvas'
import type { ApplyCanvasPlanResult, CanvasDocumentBody, CanvasNodeRef, VariantSummary } from '../../../types/video-canvas'
import { useCanvasAgent } from './useCanvasAgent'

export interface UseCanvasAssistantOptions {
  graph: { nodes: ComputedRef<GraphNode[]> }
  storyboard: Ref<CanvasStoryboard | null>
  draftId: Ref<string>
  storyboardIdRef: Ref<string>
  canvasRevision: Ref<number>
  instruction: Ref<string>
  selectedNodeIds: Ref<string[]>
  epoch: Ref<number | string>
  draftVersion: () => number | null
  flushBeforeSubmit: () => Promise<boolean>
  hasPending: () => boolean
  readonly: () => boolean
  reloadStoryboard: (storyboardId: string) => Promise<void>
  canvas: { document: Ref<CanvasDocumentBody | null>; save: (body: CanvasDocumentBody) => Promise<boolean> }
  navigateVariant: (variant: VariantSummary) => Promise<void>
  focusShot: (id: string, preparation?: boolean) => void | Promise<void>
  productionTask?: () => { id: string; shots: { takes: { id: string }[] }[] } | null
}

/** Snapshot the user's input and selection; only versions are read after the save queues finish. */
export function useCanvasAssistant(options: UseCanvasAssistantOptions) {
  const baseline = ref<CanvasStoryboard | null>(null)
  const planNodeLabels = ref<string[]>([])
  const preparedGeneration = ref<ApplyCanvasPlanResult['preparedGeneration']>(null)
  const productionSignature = () => {
    const task = options.productionTask?.()
    return task ? `${task.id}:${task.shots.flatMap(shot => shot.takes.map(take => take.id)).sort().join(',')}` : ''
  }
  let preparedAtProduction = ''
  watch(productionSignature, signature => {
    if (preparedGeneration.value && signature !== preparedAtProduction) preparedGeneration.value = null
  })
  const submitting = ref(false)
  let submissionGeneration = 0
  const labelFor = (node: GraphNode): string => {
    const shot = options.storyboard.value?.shots.find(shot => shot.id === node.refId)
    return shot ? '镜头 ' + shot.seq + '：' + shot.visual
      : node.label || (node.kind === 'note' ? '备注：' + (node.text || '空备注')
        : node.kind === 'brief' ? '创作要求' : node.kind === 'delivery' ? '交付内容' : '参考素材')
  }
  const selectedNodeLabels = computed(() => options.selectedNodeIds.value.map(id => {
    const node = options.graph.nodes.value.find(node => node.id === id)
    return node ? labelFor(node) : '节点已不存在'
  }))
  const agent = useCanvasAgent({
    draftId: options.draftId, storyboardId: options.storyboardIdRef, epoch: options.epoch,
    onApplied: async () => {
      const result = agent.appliedResult.value
      if (!result) return
      if (result.variant) { await options.navigateVariant(result.variant); return }
      if (result.preparedGeneration) {
        preparedAtProduction = productionSignature()
        preparedGeneration.value = result.preparedGeneration
        if (result.preparedGeneration.shotId) await options.focusShot(result.preparedGeneration.shotId, true)
        return
      }
      const identity = options.epoch.value
      await options.reloadStoryboard(result.storyboardId)
      if (identity !== options.epoch.value) return
      const document = options.canvas.document.value
      if (document && options.storyboard.value) {
        const ids = new Set(document.nodes.map(node => node.id))
        const added: CanvasNodeRef[] = options.storyboard.value.shots.filter(shot => !ids.has('shot:' + shot.id))
          .map(shot => ({ id: 'shot:' + shot.id, kind: 'shot', refType: 'shot', refId: shot.id,
            label: null, text: null, x: shot.x, y: shot.y }))
        if (added.length && !(await options.canvas.save({ ...document, nodes: [...document.nodes, ...added] }))) {
          agent.error.value = '计划已应用，新增镜头布局尚未保存，请重试保存'
        }
      }
      if (identity === options.epoch.value && result.affectedShotIds.length) {
        await options.focusShot(result.affectedShotIds[result.affectedShotIds.length - 1])
      }
    },
  })

  const blockedReason = computed(() => {
    if (options.readonly()) return '项目当前只读，无法应用计划'
    if (options.hasPending()) return '有未保存的修改，请先保存，再重新提出修改要求'
    const plan = agent.plan.value
    if (plan?.status === 'ready' && (plan.baseEditVersion !== options.storyboard.value?.editVersion
      || plan.baseCanvasRevision !== options.canvasRevision.value
      || (options.draftVersion() != null && plan.baseDraftVersion !== options.draftVersion()))) {
      return '项目版本已变化，此计划已失效，请重新提出修改要求'
    }
    return ''
  })

  async function submitAgent(): Promise<boolean> {
    if (!agent.active.value || submitting.value || agent.submitting.value) return false
    const instruction = options.instruction.value
    const selectedNodeIds = [...options.selectedNodeIds.value]
    if (!instruction.trim() || [...instruction.trim()].length > 2000 || selectedNodeIds.length === 0
      || selectedNodeIds.length > 20 || options.readonly()) {
      agent.error.value = '请先选择 1～20 个节点，并填写 1～2000 字的修改要求'
      return false
    }
    const identity = options.epoch.value
    const generation = ++submissionGeneration
    const labels = [...selectedNodeLabels.value]
    submitting.value = true
    try {
      const saved = await options.flushBeforeSubmit()
      if (identity !== options.epoch.value || generation !== submissionGeneration) return false
      if (!saved) { agent.error.value = '修改尚未保存，请处理保存错误后重试'; return false }
      const storyboard = options.storyboard.value
      if (!storyboard || !options.canvasRevision.value) { agent.error.value = '项目尚未就绪，请等待载入完成'; return false }
      baseline.value = JSON.parse(JSON.stringify(storyboard)) as CanvasStoryboard
      planNodeLabels.value = labels
      preparedGeneration.value = null
      await agent.submit({ selectedNodeIds, instruction, expectedEditVersion: storyboard.editVersion ?? 1,
        expectedCanvasRevision: options.canvasRevision.value })
      return identity === options.epoch.value && generation === submissionGeneration && !agent.error.value && !!agent.plan.value
    } finally { if (generation === submissionGeneration) submitting.value = false }
  }
  async function applyAgent(): Promise<boolean> {
    if (blockedReason.value) { agent.error.value = blockedReason.value; return false }
    return agent.apply()
  }
  watch(options.epoch, () => {
    submissionGeneration++; submitting.value = false; baseline.value = null
    planNodeLabels.value = []; preparedGeneration.value = null
  }, { flush: 'sync' })
  function deactivate(): void { submissionGeneration++; submitting.value = false; agent.deactivate() }
  return { agent, selectedNodeLabels, planNodeLabels, baseline, blockedReason, preparedGeneration,
    submitting, submitAgent, applyAgent, deactivate, activate: agent.activate, instruction: options.instruction }
}
