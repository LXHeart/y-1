import { storeToRefs } from 'pinia'
import { useCreditsStore } from '../stores/credits'

export type { CreditBalance, CreditHistoryItem } from '../stores/credits'

export function useCredits() {
  const store = useCreditsStore()
  return { ...store, ...storeToRefs(store) }
}
