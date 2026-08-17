import type { HotItemsProvider } from './settings'

export interface HomepageHotTags {
  industries: string[]
  city?: string
  contentType?: string
  taxonomyVersion?: string
}

export interface HomepageHotItem {
  rank: number
  title: string
  hotValue?: string
  url?: string
  cover?: string
  sourceLabel?: string
  tags?: HomepageHotTags
  validUntil?: string
  expired?: boolean
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
  /** 抓取时间（ISO 8601），用于展示热点时效（PRD §4.3） */
  fetchedAt?: string
  taxonomy?: HomepageHotTaxonomy
}

export interface HomepageHotTaxonomyOption {
  value: string
  label: string
}

export interface HomepageHotTaxonomy {
  version: string
  industries: HomepageHotTaxonomyOption[]
  cities: string[]
  contentTypes: HomepageHotTaxonomyOption[]
}

export interface HomepageHotFilters {
  industry?: string
  city?: string
  contentType?: string
  includeExpired?: boolean
}
