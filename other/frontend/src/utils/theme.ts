const THEME_MODE_KEY = 'themeMode'
const LEGACY_THEME_KEY = 'theme'

export type ThemeMode = 'light' | 'dark' | 'auto'
export type Theme = 'light' | 'dark'

export function isNightTime(): boolean {
  const hour = new Date().getHours()
  return hour >= 18 || hour < 6
}

export function resolveAutoTheme(): Theme {
  return isNightTime() ? 'dark' : 'light'
}

export function applyTheme(theme: Theme): void {
  document.documentElement.setAttribute('data-theme', theme)
  document.documentElement.setAttribute('data-prefers-color-scheme', theme)
}

export function getThemeMode(): ThemeMode {
  const stored = localStorage.getItem(THEME_MODE_KEY)
  if (stored === 'light' || stored === 'dark' || stored === 'auto') {
    return stored
  }

  // Migrate from legacy 'theme' key
  const legacy = localStorage.getItem(LEGACY_THEME_KEY)
  if (legacy === 'dark' || legacy === 'light') {
    localStorage.setItem(THEME_MODE_KEY, legacy)
    localStorage.removeItem(LEGACY_THEME_KEY)
    return legacy
  }

  return 'light'
}

export function setThemeMode(mode: ThemeMode): void {
  localStorage.setItem(THEME_MODE_KEY, mode)
}

export function resolveTheme(mode?: ThemeMode): Theme {
  const m = mode || getThemeMode()
  if (m === 'auto') return resolveAutoTheme()
  return m
}

export function initTheme(): Theme {
  const theme = resolveTheme()
  applyTheme(theme)
  return theme
}

let timerId: ReturnType<typeof setInterval> | null = null

export function startAutoThemeWatcher(onSwitch?: (theme: Theme) => void): void {
  stopAutoThemeWatcher()
  timerId = setInterval(() => {
    if (getThemeMode() !== 'auto') return
    const theme = resolveAutoTheme()
    applyTheme(theme)
    onSwitch?.(theme)
  }, 60_000)
}

export function stopAutoThemeWatcher(): void {
  if (timerId !== null) {
    clearInterval(timerId)
    timerId = null
  }
}
