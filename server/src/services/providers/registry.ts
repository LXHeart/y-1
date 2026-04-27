import type { VideoAnalysisProvider } from './types.js'

const providers = new Map<string, VideoAnalysisProvider>()

export function registerProvider(provider: VideoAnalysisProvider): void {
  providers.set(provider.id, provider)
}

export function getProvider(id: string): VideoAnalysisProvider {
  const provider = providers.get(id)
  if (!provider) {
    throw new Error(`Unknown analysis provider: ${id}`)
  }
  return provider
}

export function listProviders(): Array<{ id: string; label: string }> {
  return Array.from(providers.values()).map((p) => ({ id: p.id, label: p.label }))
}
