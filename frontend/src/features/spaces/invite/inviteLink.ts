// An invite link carries the inviteId plus the two secrets that must NEVER reach the server — the bootstrap
// secret and the one-time temp private key — so both ride the URL *fragment* (after #), which browsers don't
// send to the origin. The accept/register flow reads them back from window.location.hash.

export type InviteParts = { inviteId: string; bootstrapSecret: string; tempPrivateKey: string }

/** Build a shareable invite URL: `<origin>/#invite=<id>&s=<secret>&k=<tempPrivKey>` (secrets in the fragment). */
export function buildInviteLink(origin: string, p: InviteParts): string {
  const frag = new URLSearchParams({ invite: p.inviteId, s: p.bootstrapSecret, k: p.tempPrivateKey })
  return `${origin.replace(/\/$/, '')}/#${frag.toString()}`
}

/** Parse an invite out of a URL fragment (e.g. window.location.hash), or null if it isn't one. */
export function parseInviteHash(hash: string): InviteParts | null {
  const h = hash.startsWith('#') ? hash.slice(1) : hash
  const q = new URLSearchParams(h)
  const inviteId = q.get('invite')
  const bootstrapSecret = q.get('s')
  const tempPrivateKey = q.get('k')
  if (!inviteId || !bootstrapSecret || !tempPrivateKey) return null
  return { inviteId, bootstrapSecret, tempPrivateKey }
}
