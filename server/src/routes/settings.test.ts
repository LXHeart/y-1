import { beforeEach, describe, expect, it, vi } from 'vitest'

interface RouteLike {
  path?: string
  methods?: Record<string, boolean>
}

interface RouterLayerLike {
  name?: string
  route?: RouteLike
}

const {
  getAnalysisSettingsHandlerMock,
  updateAnalysisSettingsHandlerMock,
  listModelsHandlerMock,
  verifyModelHandlerMock,
  getHomepageSettingsHandlerMock,
  updateHomepageSettingsHandlerMock,
  requireAuthenticatedUserMock,
} = vi.hoisted(() => ({
  getAnalysisSettingsHandlerMock: vi.fn(),
  updateAnalysisSettingsHandlerMock: vi.fn(),
  listModelsHandlerMock: vi.fn(),
  verifyModelHandlerMock: vi.fn(),
  getHomepageSettingsHandlerMock: vi.fn(),
  updateHomepageSettingsHandlerMock: vi.fn(),
  requireAuthenticatedUserMock: vi.fn(),
}))

vi.mock('../controllers/settings.controller.js', () => ({
  getAnalysisSettingsHandler: getAnalysisSettingsHandlerMock,
  updateAnalysisSettingsHandler: updateAnalysisSettingsHandlerMock,
  listModelsHandler: listModelsHandlerMock,
  verifyModelHandler: verifyModelHandlerMock,
  getHomepageSettingsHandler: getHomepageSettingsHandlerMock,
  updateHomepageSettingsHandler: updateHomepageSettingsHandlerMock,
}))

vi.mock('../lib/auth.js', () => ({
  requireAuthenticatedUser: requireAuthenticatedUserMock,
}))

describe('settings routes', () => {
  beforeEach(() => {
    requireAuthenticatedUserMock.mockReset()
  })

  it('registers auth middleware before settings endpoints', async () => {
    const { settingsRouter } = await import('./settings.js')

    const layers = settingsRouter.stack as RouterLayerLike[]
    const firstRouteIndex = layers.findIndex((layer) => Boolean(layer.route))

    expect(firstRouteIndex).toBeGreaterThan(0)
  })

  it('registers GET /analysis', async () => {
    const { settingsRouter } = await import('./settings.js')
    const layer = (settingsRouter.stack as RouterLayerLike[]).find((entry) => {
      return entry.route?.path === '/analysis' && entry.route.methods?.get === true
    })

    expect(layer?.route?.path).toBe('/analysis')
  })

  it('registers PUT /analysis', async () => {
    const { settingsRouter } = await import('./settings.js')
    const layer = (settingsRouter.stack as RouterLayerLike[]).find((entry) => {
      return entry.route?.path === '/analysis' && entry.route.methods?.put === true
    })

    expect(layer?.route?.path).toBe('/analysis')
  })

  it('registers POST /analysis/models', async () => {
    const { settingsRouter } = await import('./settings.js')
    const layer = (settingsRouter.stack as RouterLayerLike[]).find((entry) => {
      return entry.route?.path === '/analysis/models' && entry.route.methods?.post === true
    })

    expect(layer?.route?.path).toBe('/analysis/models')
  })

  it('registers POST /analysis/verify-model', async () => {
    const { settingsRouter } = await import('./settings.js')
    const layer = (settingsRouter.stack as RouterLayerLike[]).find((entry) => {
      return entry.route?.path === '/analysis/verify-model' && entry.route.methods?.post === true
    })

    expect(layer?.route?.path).toBe('/analysis/verify-model')
  })

  it('registers GET /homepage', async () => {
    const { settingsRouter } = await import('./settings.js')
    const layer = (settingsRouter.stack as RouterLayerLike[]).find((entry) => {
      return entry.route?.path === '/homepage' && entry.route.methods?.get === true
    })

    expect(layer?.route?.path).toBe('/homepage')
  })

  it('registers PUT /homepage', async () => {
    const { settingsRouter } = await import('./settings.js')
    const layer = (settingsRouter.stack as RouterLayerLike[]).find((entry) => {
      return entry.route?.path === '/homepage' && entry.route.methods?.put === true
    })

    expect(layer?.route?.path).toBe('/homepage')
  })
})
