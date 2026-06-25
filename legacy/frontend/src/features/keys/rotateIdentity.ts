// uc.keys.rotate-identity (web) — replace the account identity keypair and re-wrap every owned grant to
// it. The master key (and its unlock wraps) are unchanged, so login still works; only the identity moves.
import { apiError } from '../../shared/apiError'
import * as v1 from '../../shared/crypto/v1'
import type { UnlockedSession } from '../account/login/login'
import { b64url, fromB64url } from '../../shared/base64'

type OwnedGrant = {
  scopeId: string
  kind: string
  scopePublicKey: { suiteId: string; kemPublicKey: string }
  wrappedScopePrivateKey: { suiteId: string; bytes: string }
}

const grantContext = (kind: string) => (kind === 'PERSONAL_ROOT' ? 'personal-scope-grant' : 'scope-grant')

/** Rotate the identity keypair: mint a new one, re-wrap the priv under the master key + every grant. */
export async function rotateIdentity(apiBase: string, session: UnlockedSession): Promise<{ id: string; email: string; serverRole: string }> {
  // 1. new identity, its private key wrapped under the unchanged master key
  const newId = v1.generateIdentity()
  const wrappedNewPriv = v1.symWrap(session.masterKey, v1.serializeIdentityPrivate(newId), 'identity-priv', v1.randomBytes(12))
  const newPub = { x: newId.pub.kemX, m: newId.pub.kemM }

  // 2. list every grant and re-wrap each from the OLD identity to the NEW one (per-scope context)
  const listed = await fetch(`${apiBase}/api/me/grants`, { headers: { authorization: `Bearer ${session.sessionToken}` } })
  if (!listed.ok) throw new Error(`list grants failed: ${listed.status}`)
  const owned: OwnedGrant[] = (await listed.json()).grants

  const oldKem = fromB64url(session.identityPublicKey.kemPublicKey)
  const oldPub = { x: oldKem.slice(0, 32), m: oldKem.slice(32) }
  const oldSk = v1.hybridSecret(session.identity)
  const grants = owned.map((g) => {
    const ctx = grantContext(g.kind)
    const scopePrivBytes = v1.asymUnwrap(oldSk, oldPub, fromB64url(g.wrappedScopePrivateKey.bytes), ctx)
    return {
      scopeId: g.scopeId,
      wrappedScopePrivateKey: { suiteId: 'v1', bytes: b64url(v1.asymWrap(newPub, scopePrivBytes, ctx, { nonce: v1.randomBytes(12) })) },
    }
  })

  // 3. swap the identity + grants in one request
  const res = await fetch(`${apiBase}/api/me/identity/rotate`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', authorization: `Bearer ${session.sessionToken}` },
    body: JSON.stringify({
      identityPublicKey: {
        suiteId: 'v1',
        kemPublicKey: b64url(v1.concat(newId.pub.kemX, newId.pub.kemM)),
        signPublicKey: b64url(newId.pub.sign),
      },
      wrappedIdentityPrivateKey: { suiteId: 'v1', bytes: b64url(wrappedNewPriv) },
      grants,
    }),
  })
  if (!res.ok) throw await apiError(res, 'rotate identity')
  return res.json()
}
