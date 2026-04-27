export interface DouyinHotItem {
  rank: number
  title: string
  hotValue?: string
  url?: string
  cover?: string
  source: '60sapi'
}

export interface DouyinHotItemsPayload {
  items: DouyinHotItem[]
}
