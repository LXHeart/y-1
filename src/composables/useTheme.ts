import { computed, ref, watch } from 'vue'

export type ThemeMode = 'light' | 'dark' | 'system'
type ResolvedTheme = 'light' | 'dark'

const STORAGE_KEY = 'theme-preference'

const mode = ref<ThemeMode>(loadStoredMode())
const mediaQuery = typeof window !== 'undefined'
  ? window.matchMedia('(prefers-color-scheme: dark)')
  : null

function loadStoredMode(): ThemeMode {
  try {
    const stored = localStorage.getItem(STORAGE_KEY)
    if (stored === 'light' || stored === 'dark' || stored === 'system') {
      return stored
    }
  } catch {
    // localStorage unavailable
  }
  return 'system'
}

function resolveTheme(m: ThemeMode): ResolvedTheme {
  if (m !== 'system') {
    return m
  }

  return mediaQuery?.matches ? 'dark' : 'light'
}

function applyTheme(resolved: ResolvedTheme): void {
  document.documentElement.setAttribute('data-theme', resolved)
  document.documentElement.style.colorScheme = resolved
}

const resolvedTheme = computed(() => resolveTheme(mode.value))

function setMode(newMode: ThemeMode): void {
  mode.value = newMode
}

watch(mode, (newMode) => {
  try {
    localStorage.setItem(STORAGE_KEY, newMode)
  } catch {
    // localStorage unavailable
  }

  applyTheme(resolveTheme(newMode))
}, { immediate: true })

if (mediaQuery) {
  mediaQuery.addEventListener('change', () => {
    if (mode.value === 'system') {
      applyTheme(resolveTheme('system'))
    }
  })
}

export function useTheme() {
  return {
    mode,
    resolvedTheme,
    setMode,
  }
}
