// An invite link carries the inviteId plus the secret(s) that must NEVER reach the server — the bootstrap
// secret and, for member invites, the one-time temp private key — so they ride the URL *fragment* (after #),
// which browsers don't send to the origin. The accept/register flow reads them back from window.location.hash.
//
// Two protection modes share this plumbing:
//  - pragmatic  → `k=<tempPrivKey>` (the temp key in the clear; anyone with the link can join)
//  - passphrase → `ek=<encTempKey>&ps=<salt>` (the temp key encrypted under a passphrase shared out of band;
//                 the link alone can't join — operator-blind even against whoever relays the email)

export type OwnerInviteParts = { inviteId: string; bootstrapSecret: string }
export type InviteParts = OwnerInviteParts & { tempPrivateKey: string }
export type ProtectedInviteParts = { inviteId: string; bootstrapSecret: string; encTempKey: string; pwSalt: string }
export type ParsedInvite = OwnerInviteParts | InviteParts | ProtectedInviteParts

/** True when the parsed invite is passphrase-protected (needs a passphrase to resolve the temp key). */
export function isProtectedInvite(p: ParsedInvite): p is ProtectedInviteParts {
  return 'encTempKey' in p
}

export function isMemberInvite(p: ParsedInvite): p is InviteParts {
  return 'tempPrivateKey' in p
}

/** Build a shareable member invite URL: `<origin>/#invite=<id>&s=<secret>&k=<tempPrivKey>` (secrets in the fragment). */
export function buildInviteLink(origin: string, p: InviteParts): string {
  const frag = new URLSearchParams({ invite: p.inviteId, s: p.bootstrapSecret, k: p.tempPrivateKey })
  return `${origin.replace(/\/$/, '')}/#${frag.toString()}`
}

/** Build a shareable owner-claim invite URL: no temp key exists until the invited owner claims the tenant. */
export function buildOwnerInviteLink(origin: string, p: OwnerInviteParts): string {
  const frag = new URLSearchParams({ invite: p.inviteId, s: p.bootstrapSecret })
  return `${origin.replace(/\/$/, '')}/#${frag.toString()}`
}

/** Build a passphrase-protected invite URL: the temp key rides ENCRYPTED (`ek`) with its Argon salt (`ps`). */
export function buildProtectedInviteLink(origin: string, p: ProtectedInviteParts): string {
  const frag = new URLSearchParams({ invite: p.inviteId, s: p.bootstrapSecret, ek: p.encTempKey, ps: p.pwSalt })
  return `${origin.replace(/\/$/, '')}/#${frag.toString()}`
}

/**
 * Build the native deep link that opens this invite in the veyq mobile app: `veyq://invite#…&api=<origin>`.
 * The instance origin rides along (in `api`) so the app — which may face many self-hosted instances — knows
 * which backend to register against. Everything stays in the fragment, so no secret hits any server.
 */
export function buildInviteDeepLink(origin: string, p: ParsedInvite): string {
  const base: Record<string, string> = { invite: p.inviteId, s: p.bootstrapSecret, api: origin.replace(/\/$/, '') }
  if (isProtectedInvite(p)) Object.assign(base, { ek: p.encTempKey, ps: p.pwSalt })
  else if (isMemberInvite(p)) base.k = p.tempPrivateKey
  return `veyq://invite#${new URLSearchParams(base).toString()}`
}

/** Parse an invite out of a URL fragment (e.g. window.location.hash), or null if it isn't one. */
export function parseInviteHash(hash: string): ParsedInvite | null {
  const h = hash.startsWith('#') ? hash.slice(1) : hash
  const q = new URLSearchParams(h)
  const inviteId = q.get('invite')
  const bootstrapSecret = q.get('s')
  if (!inviteId || !bootstrapSecret) return null
  const tempPrivateKey = q.get('k')
  if (tempPrivateKey) return { inviteId, bootstrapSecret, tempPrivateKey }
  const encTempKey = q.get('ek')
  const pwSalt = q.get('ps')
  if (encTempKey && pwSalt) return { inviteId, bootstrapSecret, encTempKey, pwSalt }
  return { inviteId, bootstrapSecret }
}
