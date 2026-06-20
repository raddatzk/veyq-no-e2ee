import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

describe('useThemeState', () => {
  let mediaQuery: MatchMediaMock

  beforeEach(() => {
    vi.resetModules()
    document.documentElement.className = ''
    document.documentElement.removeAttribute('data-theme')
    document.head.innerHTML = ''
    localStorage.clear()
    mediaQuery = createMatchMediaMock(false)
    vi.stubGlobal('matchMedia', vi.fn(() => mediaQuery))
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('sets the light favicon when system preference resolves to light', async () => {
    const { initializeTheme } = await import('./useThemeState')

    initializeTheme()

    const favicon = activeFavicon()
    expect(document.querySelectorAll('link[rel="icon"]')).toHaveLength(1)
    expect(favicon?.href).toContain('data:image/svg+xml')
    expect(faviconSvg(favicon)).toContain('#584fff')
  })

  it('replaces the active favicon for an explicit dark theme', async () => {
    const { initializeTheme, setThemePreference } = await import('./useThemeState')

    initializeTheme()
    const lightFavicon = activeFavicon()

    setThemePreference('dark')

    const darkFavicon = activeFavicon()
    expect(document.querySelectorAll('link[rel="icon"]')).toHaveLength(1)
    expect(darkFavicon).not.toBe(lightFavicon)
    expect(faviconSvg(darkFavicon)).toContain('#8b57ec')
    expect(localStorage.getItem('veyq:theme')).toBe('dark')
  })

  it('replaces the active favicon when switching back to light', async () => {
    const { initializeTheme, setThemePreference } = await import('./useThemeState')

    initializeTheme()
    setThemePreference('dark')
    const darkFavicon = activeFavicon()

    setThemePreference('light')

    const lightFavicon = activeFavicon()
    expect(document.querySelectorAll('link[rel="icon"]')).toHaveLength(1)
    expect(lightFavicon).not.toBe(darkFavicon)
    expect(faviconSvg(lightFavicon)).toContain('#584fff')
  })

  it('replaces the active favicon when system preference changes', async () => {
    const { initializeTheme } = await import('./useThemeState')

    initializeTheme()
    const lightFavicon = activeFavicon()
    mediaQuery.setMatches(true)

    const darkFavicon = activeFavicon()
    expect(document.querySelectorAll('link[rel="icon"]')).toHaveLength(1)
    expect(darkFavicon).not.toBe(lightFavicon)
    expect(faviconSvg(darkFavicon)).toContain('#8b57ec')
  })
})

interface MatchMediaMock extends MediaQueryList {
  setMatches(matches: boolean): void
}

function createMatchMediaMock(matches: boolean): MatchMediaMock {
  const listeners = new Set<(event: MediaQueryListEvent) => void>()
  const mediaQuery = {
    matches,
    media: '(prefers-color-scheme: dark)',
    onchange: null as ((this: MediaQueryList, event: MediaQueryListEvent) => void) | null,
    addEventListener: (_type: string, listener: (event: MediaQueryListEvent) => void) => {
      listeners.add(listener)
    },
    removeEventListener: (_type: string, listener: (event: MediaQueryListEvent) => void) => {
      listeners.delete(listener)
    },
    addListener: (listener: (event: MediaQueryListEvent) => void) => {
      listeners.add(listener)
    },
    removeListener: (listener: (event: MediaQueryListEvent) => void) => {
      listeners.delete(listener)
    },
    dispatchEvent: () => true,
    setMatches(nextMatches: boolean) {
      this.matches = nextMatches
      const event = { matches: nextMatches, media: this.media } as MediaQueryListEvent
      listeners.forEach((listener) => listener(event))
    }
  }
  return mediaQuery as MatchMediaMock
}

function activeFavicon(): HTMLLinkElement | null {
  return document.querySelector<HTMLLinkElement>('link#veyq-favicon[rel="icon"]')
}

function faviconSvg(favicon: HTMLLinkElement | null): string {
  return decodeURIComponent(favicon?.href.split(',')[1] ?? '')
}
