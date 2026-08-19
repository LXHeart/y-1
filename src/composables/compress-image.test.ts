// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { compressImageToFile } from './compress-image'

describe('compressImageToFile', () => {
  const revoke = vi.fn()
  const drawImage = vi.fn()
  const sizes = [2_000, 700]

  beforeEach(() => {
    sizes.splice(0, sizes.length, 2_000, 700)
    vi.stubGlobal('URL', { createObjectURL: vi.fn(() => 'blob:test'), revokeObjectURL: revoke })
    vi.stubGlobal('Image', class {
      naturalWidth = 1000; naturalHeight = 500; onload: null | (() => void) = null; onerror = null
      set src(_value: string) { queueMicrotask(() => this.onload?.()) }
    })
    const createElement = document.createElement.bind(document)
    vi.spyOn(document, 'createElement').mockImplementation(((tag: string) => {
      if (tag !== 'canvas') return createElement(tag)
      return {
        width: 0, height: 0,
        getContext: () => ({ drawImage }),
        toBlob: (callback: (blob: Blob | null) => void) => callback(new Blob([new Uint8Array(sizes.shift() ?? 600)])),
      } as unknown as HTMLCanvasElement
    }) as typeof document.createElement)
  })

  afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals(); revoke.mockReset(); drawImage.mockReset() })

  test('scales iteratively, emits jpeg and always releases the object URL', async () => {
    const output = await compressImageToFile(new File(['source'], 'photo.png', { type: 'image/png' }), 1_000)
    expect(output.name).toBe('photo.jpg'); expect(output.type).toBe('image/jpeg'); expect(output.size).toBe(700)
    expect(drawImage).toHaveBeenCalledTimes(2)
    expect(drawImage.mock.calls[0].slice(-2)).toEqual([1000, 500])
    expect(drawImage.mock.calls[1].slice(-2)).toEqual([800, 400])
    expect(revoke).toHaveBeenCalledWith('blob:test')
  })
})
