import { computed, ref, watch } from 'vue'
import { defineStore } from 'pinia'

export type ThemeMode = 'light' | 'dark' | 'system'
type ResolvedTheme = 'light' | 'dark'

const STORAGE_KEY = 'theme-preference'

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
  return typeof window !== 'undefined' && window.matchMedia('(prefers-color-scheme: dark)').matches
    ? 'dark'
    : 'light'
}

function applyTheme(resolved: ResolvedTheme): void {
  document.documentElement.setAttribute('data-theme', resolved)
  document.documentElement.style.colorScheme = resolved
}

export const useThemeStore = defineStore('theme', () => {
  const mode = ref<ThemeMode>(loadStoredMode())
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

  if (typeof window !== 'undefined') {
    window.matchMedia('(prefers-color-scheme: dark)')
      .addEventListener('change', () => {
        if (mode.value === 'system') {
          applyTheme(resolveTheme('system'))
        }
      })
  }

  return {
    mode,
    resolvedTheme,
    setMode,
  }
})
