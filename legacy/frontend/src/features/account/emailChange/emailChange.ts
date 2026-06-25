// uc.account.change-email (web) — change the account login email. The email is not part of key derivation
// (the KEK folds in the Secret Key + password + a random salt), so this is a pure identifier swap — no
// re-wrap. `request` (authenticated) emails a one-time token to the NEW address; `confirm` (token-only)
// commits the change, so the link works on any device regardless of login state.
import { apiError } from '../../../shared/apiError'
import type { UserSummary } from '../login/login'

/** Ask the server to email a confirmation token to a NEW address. Rejects (409) if the address is taken. */
export async function requestEmailChange(apiBase: string, sessionToken: string, newEmail: string): Promise<void> {
  const res = await fetch(`${apiBase}/api/me/email-change/request`, {
    method: 'POST',
    headers: { authorization: `Bearer ${sessionToken}`, 'content-type': 'application/json' },
    body: JSON.stringify({ newEmail: newEmail.trim() }),
  })
  if (!res.ok) throw await apiError(res, 'email change request')
}

/** Confirm an email change with the token from the email link. Token-only — no session required. */
export async function confirmEmailChange(apiBase: string, token: string): Promise<UserSummary> {
  const res = await fetch(`${apiBase}/api/me/email-change/confirm`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ token }),
  })
  if (!res.ok) throw await apiError(res, 'email change confirm')
  return res.json()
}

/** Read a `#confirm-email=<token>` token out of a URL fragment, or null. */
export function parseConfirmEmailHash(hash: string): string | null {
  const h = hash.startsWith('#') ? hash.slice(1) : hash
  return new URLSearchParams(h).get('confirm-email')
}
