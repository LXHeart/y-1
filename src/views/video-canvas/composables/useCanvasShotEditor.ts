import { nextTick, reactive, watch } from 'vue'

/**
 * 每镜草稿编辑器（任务书 #100 C100-03，§8.2）：
 * - 载入/切换镜头采用 suppress-hydration-emit——只有真实输入事件才进入 dirty（TC-007）；
 * - 切镜/切模式先 flush：保存失败停留原镜头、内容与焦点保留（TC-008）；
 * - 409 版本冲突显式标记，不自动覆盖本地稿（§4 保存状态：冲突显式载入远端由用户决定）。
 */
export interface ShotEditorFields {
  visual: string
  narration: string
  plannedSeconds: number
  cameraMove: string
}

export interface ShotSaveOutcome {
  ok: boolean
  conflict?: boolean
  message?: string
  editVersion?: number
}

export interface UseCanvasShotEditorOptions {
  /** 从当前分镜数据取镜头字段（null = 无此镜头）。 */
  loadFields(shotId: string): ShotEditorFields | null
  /** 持久化草稿（全字段载荷）；返回成败与冲突信息。 */
  save(shotId: string, fields: ShotEditorFields, expectedEditVersion: number | null): Promise<ShotSaveOutcome>
  /** 当前分镜编辑版本（保存带版本做 CAS）。 */
  currentVersion(): number | null
}

export interface ShotEditorHandle {
  state: {
    editingShotId: string | null
    draft: ShotEditorFields
    /** 载入快照：与 draft 相同则无实际变化（保存可跳过）。 */
    hydrated: ShotEditorFields
    dirty: boolean
    saving: boolean
    conflict: boolean
    errorMessage: string
  }
  beginEdit(shotId: string | null): void
  /** 刷写当前草稿；无脏或成功返回 true，失败返回 false（停留原镜头、内容保留）。 */
  flush(): Promise<boolean>
}

export const SHOT_EDITOR_DEFAULTS: ShotEditorFields = {
  visual: '',
  narration: '',
  plannedSeconds: 5,
  cameraMove: '固定机位',
}

export function useCanvasShotEditor(options: UseCanvasShotEditorOptions): ShotEditorHandle {
  const state = reactive({
    editingShotId: null as string | null,
    draft: { ...SHOT_EDITOR_DEFAULTS },
    hydrated: { ...SHOT_EDITOR_DEFAULTS },
    dirty: false,
    saving: false,
    conflict: false,
    errorMessage: '',
  })

  /** 载入抑制开关：hydration 期间的 draft 变更不进 dirty。 */
  let hydrating = false

  /** dirty = 草稿与载入快照存在差异（回退为原值即不脏——服务端也无实际变化不提升版本）。 */
  watch(() => ({ ...state.draft }), (value) => {
    if (hydrating) return
    if (!state.editingShotId) return
    state.dirty = !sameFields(value, state.hydrated)
    if (state.dirty) {
      state.errorMessage = ''
      state.conflict = false
    }
  })

  /** 载入镜头（hydration 抑制）；shotId=null 清空编辑态。 */
  function beginEdit(shotId: string | null): void {
    const fields = shotId ? options.loadFields(shotId) : null
    hydrating = true
    state.editingShotId = shotId
    state.draft = { ...(fields ?? SHOT_EDITOR_DEFAULTS) }
    state.hydrated = { ...(fields ?? SHOT_EDITOR_DEFAULTS) }
    state.dirty = false
    state.saving = false
    state.conflict = false
    state.errorMessage = ''
    void nextTick(() => {
      hydrating = false
    })
  }

  function sameFields(a: ShotEditorFields, b: ShotEditorFields): boolean {
    return a.visual === b.visual && a.narration === b.narration
      && a.plannedSeconds === b.plannedSeconds && a.cameraMove === b.cameraMove
  }

  async function flush(): Promise<boolean> {
    if (!state.dirty || !state.editingShotId) return true
    if (state.saving) return false
    state.saving = true
    state.errorMessage = ''
    try {
      // 内容与载入时相同：无实际变化，直接清脏（服务端也不会提升版本）
      if (sameFields(state.draft, state.hydrated)) {
        state.dirty = false
        state.saving = false
        return true
      }
      const outcome = await options.save(state.editingShotId, { ...state.draft },
        options.currentVersion())
      if (!outcome.ok) {
        state.saving = false
        state.conflict = outcome.conflict === true
        state.errorMessage = outcome.message || (state.conflict ? '分镜已被他人修改，请刷新后重试' : '保存失败，请重试')
        return false
      }
      state.hydrated = { ...state.draft }
      state.dirty = false
      state.conflict = false
      state.saving = false
      return true
    } catch (error: unknown) {
      state.saving = false
      state.errorMessage = error instanceof Error ? error.message : '保存失败，请重试'
      return false
    }
  }

  return { state, beginEdit, flush }
}
