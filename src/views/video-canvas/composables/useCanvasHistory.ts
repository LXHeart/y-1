import { computed, ref } from 'vue'

/**
 * 有界布局命令历史（任务书 #100 C100-02，R07）：
 * 只收布局位置变更（拖动一次一条、键盘一次一条），不把远端运行/退款/媒体删除放进本地撤销；
 * 上限 50 步与 2 MiB 序列化预算，超限淘汰最旧；新操作清空 redo；重新加载/换账号由调用方 clear()。
 */
export const HISTORY_MAX_ENTRIES = 50
export const HISTORY_MAX_BYTES = 2 * 1024 * 1024

export interface PositionChange {
  shotId: string
  from: { x: number; y: number }
  to: { x: number; y: number }
}

interface HistoryEntry {
  changes: PositionChange[]
  bytes: number
}

/** 序列化预算估算：与持久化口径一致用 JSON 字符长度。 */
export function estimateEntryBytes(changes: PositionChange[]): number {
  try {
    return JSON.stringify(changes).length
  } catch {
    return changes.length * 128
  }
}

export function useCanvasHistory() {
  /** stack[0..pointer) 为已应用（可撤销）条目，stack[pointer..] 为被撤销（可重做）条目。 */
  const stack = ref<HistoryEntry[]>([])
  const pointer = ref(0)

  const canUndo = computed(() => pointer.value > 0)
  const canRedo = computed(() => pointer.value < stack.value.length)

  /** 记录一次布局变更（空变更/零位移忽略）；清空 redo 后入栈，超限淘汰最旧。 */
  function record(changes: PositionChange[]): void {
    const meaningful = changes.filter(change => change.from.x !== change.to.x || change.from.y !== change.to.y)
    if (!meaningful.length) return
    const kept = stack.value.slice(0, pointer.value)
    kept.push({ changes: meaningful, bytes: estimateEntryBytes(meaningful) })
    while (kept.length > HISTORY_MAX_ENTRIES) kept.shift()
    while (kept.length > 1 && kept.reduce((sum, entry) => sum + entry.bytes, 0) > HISTORY_MAX_BYTES) {
      kept.shift()
    }
    stack.value = kept
    pointer.value = kept.length
  }

  /** 撤销：返回要应用的逆向变更（to→from），无可撤销返回 null。 */
  function undo(): PositionChange[] | null {
    if (pointer.value === 0) return null
    pointer.value -= 1
    const entry = stack.value[pointer.value]
    return entry.changes
      .slice()
      .reverse()
      .map(change => ({ shotId: change.shotId, from: change.to, to: change.from }))
  }

  /** 重做：返回要应用的正向变更，无可重做返回 null。 */
  function redo(): PositionChange[] | null {
    if (pointer.value >= stack.value.length) return null
    const entry = stack.value[pointer.value]
    pointer.value += 1
    return entry.changes
  }

  /** 重新加载/切换账号清空栈（R07）。 */
  function clear(): void {
    stack.value = []
    pointer.value = 0
  }

  return { canUndo, canRedo, record, undo, redo, clear }
}
