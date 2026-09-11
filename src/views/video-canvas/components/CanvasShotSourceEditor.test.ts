// @vitest-environment happy-dom
import { describe, expect, test } from 'vitest'
import { mount } from '@vue/test-utils'
import CanvasShotSourceEditor from './CanvasShotSourceEditor.vue'

/**
 * 任务书 #100 C100-13：来源编辑器界面（TC-026/027）。
 */

interface EditorMedia { id: string; name: string; isImage: boolean; durationMs: number | null; hasAudio: boolean }

function media(overrides: Partial<EditorMedia> = {}): EditorMedia {
  return { id: 'm-1', name: '门店实拍', isImage: false, durationMs: 10_000, hasAudio: true, ...overrides }
}

describe('#100 C100-13：来源编辑器', () => {
  test('generated 缺省可保存；own 未选素材禁保存（拖入≠制作来源）', async () => {
    const wrapper = mount(CanvasShotSourceEditor, {
      props: { shotId: 's-1', plannedSeconds: 5, media: null, current: null },
    })
    expect((wrapper.find('[data-test="canvas-source-save"]').element as HTMLButtonElement).disabled)
      .toBe(false)
    await wrapper.find('[data-test="canvas-source-kind-own"]').setValue('own-media')
    expect((wrapper.find('[data-test="canvas-source-save"]').element as HTMLButtonElement).disabled)
      .toBe(true)
    expect(wrapper.find('[data-test="canvas-source-no-media"]').text()).toContain('素材轨')
  })

  test('own 视频：默认 [0,5000)；滑到 2s 提交 [2000,7000)（TC-026 界面口径）', async () => {
    const wrapper = mount(CanvasShotSourceEditor, {
      props: { shotId: 's-1', plannedSeconds: 5, media: media(), current: null },
    })
    await wrapper.find('[data-test="canvas-source-kind-own"]').setValue('own-media')
    expect(wrapper.find('[data-test="canvas-source-trim-label"]').text()).toContain('0–5000 ms')
    await wrapper.find('[data-test="canvas-source-trim"]').setValue(2)
    expect(wrapper.find('[data-test="canvas-source-trim-label"]').text()).toContain('2000–7000 ms')
    await wrapper.find('[data-test="canvas-source-audio-source"]').setValue('source')
    await wrapper.find('form').trigger('submit')
    const emitted = wrapper.emitted('save') ?? []
    expect(emitted[emitted.length - 1]?.[0]).toEqual({
      kind: 'own-media', mediaId: 'm-1', trimStartMs: 2000, trimEndMs: 7000, audioMode: 'source',
    })
  })

  test('无原音禁用 source 并说明（TC-027）；素材不足禁保存', async () => {
    const silent = mount(CanvasShotSourceEditor, {
      props: { shotId: 's-1', plannedSeconds: 5, media: media({ hasAudio: false }), current: null },
    })
    await silent.find('[data-test="canvas-source-kind-own"]').setValue('own-media')
    expect((silent.find('[data-test="canvas-source-audio-source"]').element as HTMLInputElement).disabled)
      .toBe(true)
    expect(silent.text()).toContain('素材无原音')

    const short = mount(CanvasShotSourceEditor, {
      props: { shotId: 's-1', plannedSeconds: 5, media: media({ durationMs: 3000, hasAudio: true }), current: null },
    })
    await short.find('[data-test="canvas-source-kind-own"]').setValue('own-media')
    expect(short.find('[data-test="canvas-source-insufficient"]').text()).toContain('不足以截取')
    expect((short.find('[data-test="canvas-source-save"]').element as HTMLButtonElement).disabled).toBe(true)
  })

  test('图片素材：无裁剪输入、source 被替换为 narration 提交（TC-026）', async () => {
    const wrapper = mount(CanvasShotSourceEditor, {
      props: { shotId: 's-1', plannedSeconds: 5, media: media({ isImage: true, durationMs: null, hasAudio: false }), current: null },
    })
    await wrapper.find('[data-test="canvas-source-kind-own"]').setValue('own-media')
    expect(wrapper.find('[data-test="canvas-source-trim"]').exists()).toBe(false)
    // 图片无 source 选项可选——narration 提交 trim 为 null
    await wrapper.find('[data-test="canvas-source-audio-narration"]').setValue('narration')
    await wrapper.find('form').trigger('submit')
    const emitted = wrapper.emitted('save') ?? []
    expect(emitted[emitted.length - 1]?.[0]).toEqual({
      kind: 'own-media', mediaId: 'm-1', trimStartMs: null, trimEndMs: null, audioMode: 'narration',
    })
  })

  test('current 回显 own 来源（编辑态）', async () => {
    const wrapper = mount(CanvasShotSourceEditor, {
      props: {
        shotId: 's-1', plannedSeconds: 5, media: media(),
        current: { kind: 'own-media', mediaId: 'm-1', trimStartMs: 2000, trimEndMs: 7000, audioMode: 'mute' },
      },
    })
    expect((wrapper.find('[data-test="canvas-source-kind-own"]').element as HTMLInputElement).checked).toBe(true)
    expect(wrapper.find('[data-test="canvas-source-trim-label"]').text()).toContain('2000–7000 ms')
    expect((wrapper.find('[data-test="canvas-source-audio-mute"]').element as HTMLInputElement).checked).toBe(true)
  })
})
