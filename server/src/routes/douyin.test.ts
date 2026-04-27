import { beforeEach, describe, expect, it, vi } from 'vitest'

interface RouteLike {
  path?: string
  methods?: Record<string, boolean>
}

interface RouterLayerLike {
  route?: RouteLike
}

const getDouyinHotItemsHandlerMock = vi.fn()

vi.mock('../controllers/douyin.controller.js', () => ({
  analyzeDouyinVideoHandler: vi.fn(),
  downloadDouyinAudioHandler: vi.fn(),
  downloadDouyinVideoHandler: vi.fn(),
  extractDouyinVideoHandler: vi.fn(),
  getDouyinHotItemsHandler: getDouyinHotItemsHandlerMock,
  getDouyinSessionHandler: vi.fn(),
  logoutDouyinSessionHandler: vi.fn(),
  pollDouyinSessionHandler: vi.fn(),
  proxyDouyinVideoHandler: vi.fn(),
  serveDouyinAnalysisMediaHandler: vi.fn(),
  startDouyinSessionHandler: vi.fn(),
}))

describe('douyin route helpers', () => {
  beforeEach(() => {
    getDouyinHotItemsHandlerMock.mockReset()
  })

  it('registers the hot items handler on GET /hot-items', async () => {
    const { douyinRouter } = await import('./douyin.js')
    const layer = (douyinRouter.stack as RouterLayerLike[]).find((entry) => {
      return entry.route?.path === '/hot-items' && entry.route.methods?.get === true
    })
    const route = layer?.route

    expect(route?.path).toBe('/hot-items')
    expect(route?.methods?.get).toBe(true)
  })
})
