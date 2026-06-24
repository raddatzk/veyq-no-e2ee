import { apiError } from '../../shared/apiError'
import * as v1 from '../../shared/crypto/v1'
import { b64url, fromB64url } from '../../shared/base64'
import type { UnlockedSession } from '../account/login/login'

const textDecoder = new TextDecoder()
const LABEL_CONTEXT = 'login-session-label'
const LEGACY_LABEL_CONTEXT = 'login-device-name'

type NavigatorUserAgentData = {
  platform?: string
  model?: string
  brands?: Array<{ brand: string; version: string }>
  getHighEntropyValues?: (hints: string[]) => Promise<{ platform?: string; model?: string }>
}

export type LoginSessionSummary = {
  rootSessionId: string
  name?: string
  loginMethod?: string
  createdAt?: string
  lastUsedAt?: string
}

function userAgentData(): NavigatorUserAgentData | undefined {
  return (navigator as Navigator & { userAgentData?: NavigatorUserAgentData }).userAgentData
}

function isStandaloneApp(): boolean {
  const standaloneNavigator = navigator as Navigator & { standalone?: boolean }
  return standaloneNavigator.standalone === true || window.matchMedia?.('(display-mode: standalone)').matches === true
}

function browserName(): string {
  if (isStandaloneApp()) return 'veyq'
  const brands = userAgentData()?.brands ?? []
  const brand = brands.find((candidate) => !/Chromium|Not A Brand/i.test(candidate.brand))?.brand
  if (brand) return brand.replace('Microsoft Edge', 'Edge').replace('Google Chrome', 'Chrome')
  const ua = navigator.userAgent
  return /Firefox/.test(ua)
    ? 'Firefox'
    : /Edg\//.test(ua)
      ? 'Edge'
      : /Chrome/.test(ua)
        ? 'Chrome'
        : /Safari/.test(ua)
          ? 'Safari'
          : 'Browser'
}

function platformLabel(platform?: string, model?: string): string {
  if (model) return model
  const source = `${platform ?? ''} ${navigator.platform ?? ''} ${navigator.userAgent}`
  return /iPhone/.test(source)
    ? 'iPhone'
    : /iPad|Macintosh.*Mobile/.test(source)
      ? 'iPad'
      : /Android/.test(source)
        ? 'Android'
        : /Mac|macOS|Macintosh/.test(source)
          ? 'Mac'
          : /Win|Windows/.test(source)
            ? 'Windows PC'
            : /Linux/.test(source)
              ? 'Linux device'
              : 'Device'
}

function possessiveName(name?: string): string {
  const trimmed = name?.trim()
  if (!trimmed) return ''
  return trimmed.endsWith('s') ? `${trimmed}'` : `${trimmed}'s`
}

export async function defaultSessionLabel(ownerName?: string): Promise<string> {
  const client = browserName()
  let platform = userAgentData()?.platform
  let model = userAgentData()?.model
  try {
    const values = await userAgentData()?.getHighEntropyValues?.(['platform', 'model'])
    platform = values?.platform ?? platform
    model = values?.model ?? model
  } catch {
    // Client hints are optional; the user agent fallback still gives a readable client class.
  }
  const device = platformLabel(platform, model)
  const owner = possessiveName(ownerName)
  return owner ? `${client} on ${owner} ${device}` : `${client} on ${device}`
}

export function fallbackSessionLabel(): string {
  return `${browserName()} on ${platformLabel(userAgentData()?.platform, userAgentData()?.model)}`
}

function sealSessionLabel(masterKey: Uint8Array, name: string): string {
  return b64url(v1.symWrap(masterKey, v1.utf8(name), LABEL_CONTEXT, v1.randomBytes(12)))
}

function openSessionLabel(masterKey: Uint8Array, encryptedLabel: string): string {
  for (const context of [LABEL_CONTEXT, LEGACY_LABEL_CONTEXT]) {
    try {
      return textDecoder.decode(v1.symUnwrap(masterKey, fromB64url(encryptedLabel), context))
    } catch {
      /* try the next context */
    }
  }
  return ''
}

export async function registerLoginSession(
  apiBase: string,
  session: UnlockedSession,
): Promise<void> {
  try {
    const name = await defaultSessionLabel(session.user.displayName)
    const res = await fetch(`${apiBase}/api/me/login-sessions/${encodeURIComponent(session.rootSessionId)}`, {
      method: 'PUT',
      headers: { 'content-type': 'application/json', authorization: `Bearer ${session.sessionToken}` },
      body: JSON.stringify({ encryptedLabel: sealSessionLabel(session.masterKey, name) }),
    })
    if (!res.ok) throw await apiError(res, 'login session register')
  } catch {
    // Non-fatal: the user is logged in regardless of whether the session label updated.
  }
}

export async function listLoginSessions(apiBase: string, session: UnlockedSession): Promise<LoginSessionSummary[]> {
  const res = await fetch(`${apiBase}/api/me/login-sessions`, { headers: { authorization: `Bearer ${session.sessionToken}` } })
  if (!res.ok) throw await apiError(res, 'login sessions')
  const body: {
    sessions: Array<{
      rootSessionId: string
      encryptedLabel: string
      loginMethod: string
      createdAt: string
      lastUsedAt: string
    }>
  } = await res.json()
  return body.sessions.map((entry) => ({
    rootSessionId: entry.rootSessionId,
    name: openSessionLabel(session.masterKey, entry.encryptedLabel) || fallbackSessionLabel(),
    loginMethod: entry.loginMethod,
    createdAt: entry.createdAt,
    lastUsedAt: entry.lastUsedAt,
  }))
}

export async function renameLoginSession(
  apiBase: string,
  session: UnlockedSession,
  rootSessionId: string,
  name: string,
): Promise<void> {
  const res = await fetch(`${apiBase}/api/me/login-sessions/${encodeURIComponent(rootSessionId)}`, {
    method: 'PUT',
    headers: { 'content-type': 'application/json', authorization: `Bearer ${session.sessionToken}` },
    body: JSON.stringify({ encryptedLabel: sealSessionLabel(session.masterKey, name) }),
  })
  if (!res.ok) throw await apiError(res, 'login session rename')
}

export async function removeLoginSession(apiBase: string, sessionToken: string, rootSessionId: string): Promise<void> {
  const res = await fetch(`${apiBase}/api/me/login-sessions/${encodeURIComponent(rootSessionId)}`, {
    method: 'DELETE',
    headers: { authorization: `Bearer ${sessionToken}` },
  })
  if (!res.ok) throw await apiError(res, 'login session removal')
}
