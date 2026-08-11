import { storeToRefs } from 'pinia'
import { useAnalysisSettingsStore } from '../stores/analysis-settings'

export function useAnalysisSettings() {
  const store = useAnalysisSettingsStore()
  return { ...store, ...storeToRefs(store) }
}
