export const VEYQ_OPEN_SCHEME = 'veyq://open'

export function isIOSUserAgent(userAgent: string) {
  return /iPad|iPhone|iPod/.test(userAgent)
}

export function isIOSNavigator(navigatorRef: Pick<Navigator, 'userAgent' | 'platform' | 'maxTouchPoints'>) {
  return isIOSUserAgent(navigatorRef.userAgent) || (navigatorRef.platform === 'MacIntel' && navigatorRef.maxTouchPoints > 1)
}

export function canonicalAppURL(location: Pick<Location, 'protocol' | 'host' | 'pathname' | 'search' | 'hash'>) {
  if (location.protocol !== 'https:' && location.protocol !== 'http:') return ''
  return `${location.protocol}//${location.host}${location.pathname}${location.search}${location.hash}`
}

export function veyqOpenURL(canonicalURL: string) {
  return `${VEYQ_OPEN_SCHEME}?url=${encodeURIComponent(canonicalURL)}`
}

export function smartAppBannerContent(appStoreId: string, canonicalURL: string) {
  const trimmedAppStoreId = appStoreId.trim()
  if (!trimmedAppStoreId || !canonicalURL) return ''
  return `app-id=${trimmedAppStoreId}, app-argument=${canonicalURL}`
}

export function upsertSmartAppBanner(appStoreId: string, canonicalURL: string, documentRef: Document = document) {
  const content = smartAppBannerContent(appStoreId, canonicalURL)
  const existing = documentRef.querySelector<HTMLMetaElement>('meta[name="apple-itunes-app"]')
  if (!content) {
    existing?.remove()
    return
  }

  const meta = existing ?? documentRef.createElement('meta')
  meta.name = 'apple-itunes-app'
  meta.content = content
  if (!existing) {
    documentRef.head.append(meta)
  }
}
