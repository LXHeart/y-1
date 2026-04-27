import type { Dispatcher } from 'undici'

export interface ProviderFetchRequestInit extends RequestInit {
  dispatcher?: Dispatcher
}

export function providerFetch(input: string | URL | Request, init?: ProviderFetchRequestInit): Promise<Response> {
  return fetch(input, init as RequestInit)
}
