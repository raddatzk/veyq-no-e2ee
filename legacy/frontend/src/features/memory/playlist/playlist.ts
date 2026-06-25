// uc.memory.link-playlists (web, ADR 0010 operator-blind) — link streaming playlists to a Memory. The payload
// {provider, url, title?, note?} is sealed under the scope content key (the server is blind to it); only the
// benign `featured` flag is server-visible. Provider is detected client-side from the URL host.
import { apiError } from '../../../shared/apiError'
import { authFetch } from '../../../shared/api/sessionFetch'
import * as v1 from '../../../shared/crypto/v1'
import { b64url, fromB64url } from '../../../shared/base64'
import type { UnlockedSession } from '../../account/login/login'
import type { ActiveSpace } from '../../home/home'

const EMPTY = new Uint8Array(0)

export type PlaylistProvider = 'SPOTIFY' | 'APPLE_MUSIC' | 'YOUTUBE' | 'SOUNDCLOUD' | 'DEEZER' | 'OTHER'
export type Playlist = { id: string; featured: boolean; provider: PlaylistProvider; url: string; title?: string; note?: string }
type Payload = { provider: PlaylistProvider; url: string; title?: string; note?: string }

/** Detect the streaming provider from a URL host (best-effort; OTHER for anything else). */
export function detectProvider(url: string): PlaylistProvider {
  let host = ''
  try { host = new URL(url).host.toLowerCase() } catch { return 'OTHER' }
  if (host.includes('spotify.com')) return 'SPOTIFY'
  if (host.includes('music.apple.com')) return 'APPLE_MUSIC'
  if (host.includes('youtube.com') || host.includes('youtu.be')) return 'YOUTUBE'
  if (host.includes('soundcloud.com')) return 'SOUNDCLOUD'
  if (host.includes('deezer.com')) return 'DEEZER'
  return 'OTHER'
}

export const PROVIDER_LABEL: Record<PlaylistProvider, string> = {
  SPOTIFY: 'Spotify', APPLE_MUSIC: 'Apple Music', YOUTUBE: 'YouTube', SOUNDCLOUD: 'SoundCloud', DEEZER: 'Deezer', OTHER: 'Link',
}

function seal(contentKey: Uint8Array, payload: Payload): { suiteId: string; bytes: string } {
  const nonce = v1.randomBytes(12)
  return { suiteId: 'v1', bytes: b64url(v1.concat(nonce, v1.aeadSeal(contentKey, nonce, v1.utf8(JSON.stringify(payload)), EMPTY))) }
}
function open(contentKey: Uint8Array, bytes: string): Payload {
  const sealed = fromB64url(bytes)
  return JSON.parse(new TextDecoder().decode(v1.aeadOpen(contentKey, sealed.slice(0, 12), sealed.slice(12), EMPTY)))
}

function toPlaylist(contentKey: Uint8Array, p: { id: string; featured: boolean; sealedContent: { bytes: string } }): Playlist {
  const pl = open(contentKey, p.sealedContent.bytes)
  return { id: p.id, featured: p.featured, provider: pl.provider, url: pl.url, title: pl.title, note: pl.note }
}

export async function listPlaylists(apiBase: string, session: UnlockedSession, active: ActiveSpace, nodeId: string): Promise<Playlist[]> {
  const res = await authFetch(apiBase, session, `/api/scopes/${active.scope.scopeId}/nodes/${nodeId}/playlists`)
  if (!res.ok) throw await apiError(res, 'list playlists')
  return (await res.json()).playlists.map((p: never) => toPlaylist(active.contentKey, p))
}

export async function createPlaylist(apiBase: string, session: UnlockedSession, active: ActiveSpace, nodeId: string, url: string, title: string, note: string, featured: boolean): Promise<void> {
  const sealedContent = seal(active.contentKey, { provider: detectProvider(url), url, title: title || undefined, note: note || undefined })
  const res = await authFetch(apiBase, session, `/api/scopes/${active.scope.scopeId}/nodes/${nodeId}/playlists`, {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ sealedContent, featured }),
  })
  if (!res.ok) throw await apiError(res, 'create playlist')
}

export async function updatePlaylist(apiBase: string, session: UnlockedSession, active: ActiveSpace, nodeId: string, p: Playlist): Promise<void> {
  const sealedContent = seal(active.contentKey, { provider: detectProvider(p.url), url: p.url, title: p.title || undefined, note: p.note || undefined })
  const res = await authFetch(apiBase, session, `/api/scopes/${active.scope.scopeId}/nodes/${nodeId}/playlists/${p.id}`, {
    method: 'PATCH', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ sealedContent, featured: p.featured }),
  })
  if (!res.ok) throw await apiError(res, 'update playlist')
}

export async function setPlaylistFeatured(apiBase: string, session: UnlockedSession, active: ActiveSpace, nodeId: string, playlistId: string, featured: boolean): Promise<void> {
  const res = await authFetch(apiBase, session, `/api/scopes/${active.scope.scopeId}/nodes/${nodeId}/playlists/${playlistId}`, {
    method: 'PATCH', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ featured }),
  })
  if (!res.ok) throw await apiError(res, 'feature playlist')
}

export async function deletePlaylist(apiBase: string, session: UnlockedSession, active: ActiveSpace, nodeId: string, playlistId: string): Promise<void> {
  const res = await authFetch(apiBase, session, `/api/scopes/${active.scope.scopeId}/nodes/${nodeId}/playlists/${playlistId}`, { method: 'DELETE' })
  if (!res.ok) throw await apiError(res, 'delete playlist')
}
