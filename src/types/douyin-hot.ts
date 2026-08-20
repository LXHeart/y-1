export interface DouyinHotItem {
  rank: number
  title: string
  hotValue?: string
  url?: string
  cover?: string
  source?: string
}

export interface DouyinHotItemsPayload {
  items: DouyinHotItem[]
}
