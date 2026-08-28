// @vitest-environment happy-dom
import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  clearChunkRetryMarker, isChunkLoadError, readChunkRetryTarget, reloadOnChunkError,
} from './chunk-reload'

describe('chunk reload self-heal', () => {
  afterEach(() => {
    window.history.replaceState(null, '', '/')
    vi.restoreAllMocks()
  })

  it('recognizes chunk load failures across engine dialects', () => {
    expect(isChunkLoadError(new Error('Failed to fetch dynamically imported module "x"'))).toBe(true)
    expect(isChunkLoadError(new Error('error loading dynamically imported module "x"'))).toBe(true)
    expect(isChunkLoadError(new Error('Importing a module script failed.'))).toBe(true)
    // 服务器把缺失 chunk 回退成 HTML 时的 MIME 拒绝
    expect(isChunkLoadError(new Error(
      'Failed to load module script: Expected a JavaScript module script but the server responded with a MIME type of "text/html"',
    ))).toBe(true)
    expect(isChunkLoadError(new Error('NetworkError when loading chunk'))).toBe(false)
    expect(isChunkLoadError(undefined)).toBe(false)
    expect(isChunkLoadError('plain string')).toBe(false)
  })

  it('navigates with a retry marker instead of reloading the raw URL', () => {
    const replace = vi.spyOn(window.location, 'replace').mockImplementation(() => {})
    window.history.replaceState(null, '', '/admin')
    reloadOnChunkError(new Error('Failed to fetch dynamically imported module'), '/ops')
    expect(replace).toHaveBeenCalledTimes(1)
    const target = String(replace.mock.calls[0][0])
    expect(target).toContain('chunk_retry=%2Fops')
    expect(target).toMatch(/\/admin\?/)
  })

  it('reads the target without clearing the marker', () => {
    window.history.replaceState(null, '', '/ops?chunk_retry=%2Fadmin&x=1')
    expect(readChunkRetryTarget()).toBe('/admin')
    expect(window.location.search).toBe('?chunk_retry=%2Fadmin&x=1')
  })

  it('returns null when no marker or target is not a path', () => {
    window.history.replaceState(null, '', '/ops')
    expect(readChunkRetryTarget()).toBeNull()
    window.history.replaceState(null, '', '/ops?chunk_retry=javascript%3Aalert(1)')
    expect(readChunkRetryTarget()).toBeNull()
  })

  it('clears the marker only on demand, keeping other params', () => {
    window.history.replaceState(null, '', '/ops?chunk_retry=%2Fadmin&x=1')
    clearChunkRetryMarker()
    expect(window.location.search).toBe('?x=1')
  })

  it('does not loop when the retry already happened in this page load', () => {
    const replace = vi.spyOn(window.location, 'replace').mockImplementation(() => {})
    window.history.replaceState(null, '', '/ops?chunk_retry=%2Fadmin')
    reloadOnChunkError(new Error('Failed to fetch dynamically imported module'), '/admin')
    expect(replace).not.toHaveBeenCalled()
  })

  it('ignores unrelated navigation errors', () => {
    const replace = vi.spyOn(window.location, 'replace').mockImplementation(() => {})
    window.history.replaceState(null, '', '/admin')
    reloadOnChunkError(new Error('NetworkError when loading chunk'), '/ops')
    expect(replace).not.toHaveBeenCalled()
  })
})
