import { describe, expect, it } from 'vitest'
import {
  canonicalAppURL,
  isIOSNavigator,
  isIOSUserAgent,
  smartAppBannerContent,
  upsertSmartAppBanner,
  veyqOpenURL
} from './appLinks'

describe('appLinks', () => {
  it('detects iOS browser user agents', () => {
    expect(isIOSUserAgent('Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X)')).toBe(true)
    expect(isIOSUserAgent('Mozilla/5.0 (Linux; Android 15; Pixel 9)')).toBe(false)
    expect(isIOSNavigator({ userAgent: 'Mozilla/5.0 (Macintosh)', platform: 'MacIntel', maxTouchPoints: 5 })).toBe(
      true
    )
  })

  it('builds canonical http app urls from the current location', () => {
    expect(
      canonicalAppURL({
        protocol: 'https:',
        host: 'memories.example.test',
        pathname: '/summer',
        search: '?view=gallery',
        hash: '#journal'
      } as Location)
    ).toBe('https://memories.example.test/summer?view=gallery#journal')
  })

  it('rejects non-http locations', () => {
    expect(
      canonicalAppURL({
        protocol: 'file:',
        host: '',
        pathname: '/tmp/index.html',
        search: '',
        hash: ''
      } as Location)
    ).toBe('')
  })

  it('encodes canonical links as veyq open urls', () => {
    expect(veyqOpenURL('https://memories.example.test/summer?view=gallery#journal')).toBe(
      'veyq://open?url=https%3A%2F%2Fmemories.example.test%2Fsummer%3Fview%3Dgallery%23journal'
    )
  })

  it('creates Safari Smart App Banner content only when configured', () => {
    expect(smartAppBannerContent('1234567890', 'https://memories.example.test/summer')).toBe(
      'app-id=1234567890, app-argument=https://memories.example.test/summer'
    )
    expect(smartAppBannerContent('', 'https://memories.example.test/summer')).toBe('')
  })

  it('upserts and removes Safari Smart App Banner metadata', () => {
    const documentRef = document.implementation.createHTMLDocument('veyq')
    upsertSmartAppBanner('1234567890', 'https://memories.example.test/summer', documentRef)
    expect(documentRef.querySelector('meta[name="apple-itunes-app"]')?.getAttribute('content')).toBe(
      'app-id=1234567890, app-argument=https://memories.example.test/summer'
    )

    upsertSmartAppBanner('', 'https://memories.example.test/summer', documentRef)
    expect(documentRef.querySelector('meta[name="apple-itunes-app"]')).toBeNull()
  })
})
