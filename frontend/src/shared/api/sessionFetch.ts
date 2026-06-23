import type { UnlockedSession } from '../../features/account/login/login'
import { setUnlocked } from '../../app/session'

type RefreshSessionResponse = {
  sessionToken: string
}

let refreshRequest: Promise<string> | null = null

/** Fetch with the current Bearer token; on an expired access token, rotate via the HttpOnly refresh cookie once. */
export async function authFetch(
  apiBase: string,
  session: UnlockedSession,
  path: string,
  options: RequestInit = {},
): Promise<Response> {
  let response = await fetch(`${apiBase}${path}`, withAuth(session, options))
  if (response.status !== 401 || !canRefresh(path)) return response

  try {
    await refreshAccessSession(apiBase, session)
  } catch {
    return response
  }

  response = await fetch(`${apiBase}${path}`, withAuth(session, options))
  return response
}

export function authHeader(session: UnlockedSession): { authorization: string } {
  return { authorization: `Bearer ${session.sessionToken}` }
}

function withAuth(session: UnlockedSession, options: RequestInit): RequestInit {
  const headers = new Headers(options.headers)
  headers.set('authorization', `Bearer ${session.sessionToken}`)
  return {
    ...options,
    headers,
  }
}

async function refreshAccessSession(apiBase: string, session: UnlockedSession): Promise<string> {
  refreshRequest ||= fetch(`${apiBase}/api/auth/refresh`, {
    method: 'POST',
    credentials: 'include',
  })
    .then(async (response) => {
      if (!response.ok) throw new Error(`refresh session failed: ${response.status}`)
      const body = (await response.json()) as RefreshSessionResponse
      if (!body.sessionToken) throw new Error('refresh session returned no session token')
      return body.sessionToken
    })
    .finally(() => {
      refreshRequest = null
    })

  const sessionToken = await refreshRequest
  session.sessionToken = sessionToken
  setUnlocked({ ...session, sessionToken })
  return sessionToken
}

function canRefresh(path: string): boolean {
  return path !== '/api/login' && path !== '/api/auth/refresh' && path !== '/api/me/session'
}
