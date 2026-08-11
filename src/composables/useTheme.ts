import { storeToRefs } from 'pinia'
import { useThemeStore } from '../stores/theme'

export type { ThemeMode } from '../stores/theme'

export function useTheme() {
  const store = useThemeStore()
  return { ...store, ...storeToRefs(store) }
}
