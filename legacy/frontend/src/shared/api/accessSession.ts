const ACCESS_TOKEN_EXPIRES_AT_KEY = 'veyq.accessTokenExpiresAt'
const REFRESH_SKEW_MS = 5_000

export type AccessSession = {
  accessTokenExpiresAt: string
}

export function rememberAccessSession(session: AccessSession) {
  storage()?.setItem(ACCESS_TOKEN_EXPIRES_AT_KEY, session.accessTokenExpiresAt)
}

export function clearAccessSession() {
  storage()?.removeItem(ACCESS_TOKEN_EXPIRES_AT_KEY)
}

export function hasExpiredAccessSession(now = Date.now()): boolean {
  const expiresAt = storage()?.getItem(ACCESS_TOKEN_EXPIRES_AT_KEY)
  if (!expiresAt) return false

  const expiresAtMs = Date.parse(expiresAt)
  if (Number.isNaN(expiresAtMs)) return true

  return expiresAtMs <= now + REFRESH_SKEW_MS
}

function storage(): Storage | null {
  try {
    return globalThis.localStorage ?? null
  } catch {
    return null
  }
}
