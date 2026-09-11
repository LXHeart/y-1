// @vitest-environment happy-dom
import { describe, expect, test } from 'vitest'
import { nextTick } from 'vue'
import { useCanvasShotEditor } from './useCanvasShotEditor'
import type { ShotEditorFields, ShotSaveOutcome, UseCanvasShotEditorOptions } from './useCanvasShotEditor'

function fields(overrides: Partial<ShotEditorFields> = {}): ShotEditorFields {
  return { visual: '画面', narration: '旁白', plannedSeconds: 5, cameraMove: '固定机位', ...overrides }
}

function makeEditor(options: Partial<UseCanvasShotEditorOptions> = {}) {
  const store: Record<string, ShotEditorFields> = {
    'shot-1': fields(),
    'shot-2': fields({ visual: '画面二', cameraMove: '环绕' }),
  }
  const saveCalls: Array<{ shotId: string; fields: ShotEditorFields; version: number | null }> = []
  let version = 3
  const save = options.save ?? (async (shotId: string, sent: ShotEditorFields, expected: number | null) => {
    saveCalls.push({ shotId, fields: sent, version: expected })
    const outcome: ShotSaveOutcome = { ok: true, editVersion: ++version }
    return outcome
  })
  const editor = useCanvasShotEditor({
    loadFields: shotId => store[shotId] ?? null,
    save,
    currentVersion: options.currentVersion ?? (() => 2),
  })
  return { editor, store, saveCalls }
}

async function tick(): Promise<void> {
  await nextTick()
  await nextTick()
}

describe('TC-007：镜头载入不误标脏，真实输入才进入 dirty', () => {
  test('载入/切换镜头（hydration）不置脏不发保存', async () => {
    const { editor } = makeEditor()
    editor.beginEdit('shot-1')
    await tick()
    expect(editor.state.dirty).toBe(false)
    expect(editor.state.editingShotId).toBe('shot-1')

    editor.beginEdit('shot-2')
    await tick()
    expect(editor.state.dirty).toBe(false)
    expect(editor.state.draft.visual).toBe('画面二')
  })

  test('真实输入进入 dirty；输入与原值相同的内容 flush 直接清脏（无实际变化不保存）', async () => {
    const { editor, saveCalls } = makeEditor()
    editor.beginEdit('shot-1')
    await tick()

    editor.state.draft.visual = '新画面'
    await tick()
    expect(editor.state.dirty).toBe(true)

    // 与载入时相同的内容：flush 不发请求、清脏
    editor.state.draft.visual = '画面'
    const ok = await editor.flush()
    expect(ok).toBe(true)
    expect(saveCalls).toHaveLength(0)
    expect(editor.state.dirty).toBe(false)
  })
})

describe('TC-008：保存失败停留原镜头、内容与错误保留；成功才允许切换', () => {
  test('服务端 500：flush 失败、草稿保留、可重试成功', async () => {
    let fail = true
    const { editor } = makeEditor({
      save: async () => (fail ? { ok: false, message: '服务暂不可用' } : { ok: true, editVersion: 4 }),
    })
    editor.beginEdit('shot-1')
    await tick()
    editor.state.draft.visual = '改过的画面'
    await tick()

    expect(await editor.flush()).toBe(false)
    expect(editor.state.dirty).toBe(true)
    expect(editor.state.draft.visual).toBe('改过的画面')
    expect(editor.state.errorMessage).toBe('服务暂不可用')
    expect(editor.state.editingShotId).toBe('shot-1')

    fail = false
    expect(await editor.flush()).toBe(true)
    expect(editor.state.dirty).toBe(false)
    expect(editor.state.errorMessage).toBe('')
  })

  test('切镜 flush 闸：失败不 beginEdit 新镜头（视图据 false 停留）', async () => {
    const { editor } = makeEditor({
      save: async () => ({ ok: false, message: '网络中断' }),
    })
    editor.beginEdit('shot-1')
    await tick()
    editor.state.draft.visual = '未保存的输入'
    await tick()

    const flushed = await editor.flush()
    if (!flushed) {
      // 模拟视图行为：不切换
      expect(editor.state.editingShotId).toBe('shot-1')
      expect(editor.state.draft.visual).toBe('未保存的输入')
      return
    }
    throw new Error('flush 应当失败')
  })

  test('保存中重复 flush 被拒（不并发保存）', async () => {
    let release: ((outcome: ShotSaveOutcome) => void) | null = null
    const gate = new Promise<ShotSaveOutcome>(resolve => { release = resolve })
    const { editor } = makeEditor({
      save: async () => gate,
    })
    editor.beginEdit('shot-1')
    await tick()
    editor.state.draft.visual = 'x'
    await tick()

    const first = editor.flush()
    expect(await editor.flush()).toBe(false) // 在途不重复
    release!({ ok: true, editVersion: 4 })
    expect(await first).toBe(true)
  })
})

describe('TC-009 前端面：版本冲突（409）显式标记，不自动覆盖本地稿', () => {
  test('conflict 落位、内容保留、错误文案带冲突语义', async () => {
    const { editor } = makeEditor({
      save: async () => ({ ok: false, conflict: true, message: '分镜已被他人修改，请刷新后重试' }),
    })
    editor.beginEdit('shot-1')
    await tick()
    editor.state.draft.visual = '本地修改'
    await tick()

    expect(await editor.flush()).toBe(false)
    expect(editor.state.conflict).toBe(true)
    expect(editor.state.draft.visual).toBe('本地修改')
    expect(editor.state.errorMessage).toContain('他人修改')
  })

  test('保存携带当前版本（服务端 CAS 依据）', async () => {
    const { editor, saveCalls } = makeEditor({ currentVersion: () => 7 })
    editor.beginEdit('shot-1')
    await tick()
    editor.state.draft.visual = 'v2'
    await tick()
    expect(await editor.flush()).toBe(true)
    expect(saveCalls[0]?.version).toBe(7)
    expect(saveCalls[0]?.fields).toEqual(fields({ visual: 'v2' }))
  })
})
