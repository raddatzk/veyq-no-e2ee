import { describe, expect, it } from 'vitest'
import { detectProvider, PROVIDER_LABEL, type PlaylistProvider } from './playlist'

// uc.memory.link-playlists (web): provider is detected client-side from the URL host; the sealed
// payload {provider,url,...} is opaque to the server. Pure logic — no network.
describe('playlist provider detection', () => {
  it('detects Spotify, Apple Music, YouTube, SoundCloud and Deezer from the URL host', () => {
    expect(detectProvider('https://open.spotify.com/playlist/37i9dQ')).toBe('SPOTIFY')
    expect(detectProvider('https://music.apple.com/us/playlist/x/pl.u-123')).toBe('APPLE_MUSIC')
    expect(detectProvider('https://www.youtube.com/playlist?list=PLabc')).toBe('YOUTUBE')
    expect(detectProvider('https://youtu.be/dQw4w9WgXcQ')).toBe('YOUTUBE')
    expect(detectProvider('https://soundcloud.com/artist/sets/mix')).toBe('SOUNDCLOUD')
    expect(detectProvider('https://www.deezer.com/en/playlist/123')).toBe('DEEZER')
  })

  it('falls back to OTHER for an unknown host', () => {
    expect(detectProvider('https://example.com/track/1')).toBe('OTHER')
  })

  it('returns OTHER for a malformed URL (never throws)', () => {
    expect(detectProvider('not a url')).toBe('OTHER')
    expect(detectProvider('')).toBe('OTHER')
  })

  it('has a label for every provider', () => {
    const providers: PlaylistProvider[] = ['SPOTIFY', 'APPLE_MUSIC', 'YOUTUBE', 'SOUNDCLOUD', 'DEEZER', 'OTHER']
    for (const p of providers) expect(PROVIDER_LABEL[p]).toBeTruthy()
  })
})
