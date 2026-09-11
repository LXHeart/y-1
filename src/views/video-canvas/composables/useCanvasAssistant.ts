import { computed, ref } from 'vue'
import type { ComputedRef, Ref } from 'vue'
import type { GraphNode } from './useCanvasGraph'
import type { CanvasStoryboard } from '../useVideoCanvas'
import { useCanvasAgent } from './useCanvasAgent'

/**
 * 任务书 #100 C100-18：画布 AI 助手装配（视图体积门禁下沉——视图只持开关/输入）。
 * epoch = draftId+editVersion：项目/版本切换丢弃旧计划响应。
 */
export interface UseCanvasAssistantOptions {
  graph: { nodes: ComputedRef<GraphNode[]> }
  storyboard: Ref<CanvasStoryboard | null>
  draftId: Ref<string>
  storyboardIdRef: Ref<string>
  canvasRevision: Ref<number>
  reloadStoryboard: (storyboardId: string) => Promise<void>
}

export function useCanvasAssistant(options: UseCanvasAssistantOptions) {
  const { graph, storyboard, draftId, storyboardIdRef, canvasRevision, reloadStoryboard } = options

  const instruction = ref('')
  const selectedNodeLabels = computed(() =>
    graph.nodes.value
      .filter(node => node.kind === 'shot')
      .map(node => `镜头 ${node.refId?.slice(0, 8) ?? node.id}`))

  const agent = useCanvasAgent({
    draftId,
    storyboardId: storyboardIdRef,
    epoch: computed(() => `${draftId.value}:${storyboard.value?.editVersion ?? 0}`),
    onApplied: async () => {
      await reloadStoryboard(storyboardIdRef.value)
    },
  })

  function submitAgent(): void {
    void agent.submit({
      selectedNodeIds: graph.nodes.value.filter(node => node.kind === 'shot').map(node => node.id),
      expectedEditVersion: storyboard.value?.editVersion ?? 1,
      expectedCanvasRevision: canvasRevision.value,
      instruction: instruction.value,
    })
  }

  return { agent, selectedNodeLabels, submitAgent, instruction }
}
