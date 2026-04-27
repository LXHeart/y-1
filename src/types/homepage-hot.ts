import type { HotItemsProvider } from './settings'

export interface HomepageHotItem {
  rank: number
  title: string
  hotValue?: string
  url?: string
  cover?: string
  sourceLabel?: string
}

export interface HomepageHotItemGroup {
  platform: string
  label: string
  items: HomepageHotItem[]
}

export interface HomepageHotItemsPayload {
  provider: HotItemsProvider
  items: HomepageHotItem[]
  groups?: HomepageHotItemGroup[]
}
