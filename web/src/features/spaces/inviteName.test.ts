import { describe, it, expect, vi, beforeEach } from 'vitest'

// The invitee is a real *foreign* identity. openInviteeName reads the caller's own identity from the vault, so we
// mock it to hand back a fresh copy of that identity's private bundle each call (the real vault does too —
// openInviteeName zeroes the copy it gets). A "locked" run returns null.
let vaultPriv: (() => ReturnType<typeof cloneRecipientPriv> | null) = () => null
vi.mock('../../app/vault', () => ({ identityPrivate: () => vaultPriv() }))

import { deriveGroupNameKey, wrapNameKeyToInvitee, openInviteeName, GROUP_NAME_KEY } from './inviteName'
import { sealName, openName } from '../memory/tree'
import { SCOPE_GRANT } from './grant'
import * as v1 from '../../shared/crypto/v1'
import { b64std, fromB64std } from '../../shared/base64'

const recipient = v1.generateIdentity()
const recipientPubB64 = b64std(v1.serializeIdentityPublic(recipient.pub))

// A fresh copy of the recipient's private bundle (openInviteeName wipes what the vault hands it).
function cloneRecipientPriv() {
  return {
    kemX: recipient.priv.kemX.slice(),
    mlkemSeed: recipient.priv.mlkemSeed.slice(),
    sign: recipient.priv.sign.slice(),
  }
}

beforeEach(() => {
  vaultPriv = () => null
})

describe('group invite name-key (cr-0096, ADR 0027)', () => {
  it('derives K_name deterministically from the epoch key, one-way (not equal to the epoch key)', () => {
    const epochKey = v1.randomBytes(32)
    const a = deriveGroupNameKey(epochKey)
    const b = deriveGroupNameKey(epochKey)
    expect(b64std(a)).toBe(b64std(b)) // deterministic
    expect(b64std(a)).not.toBe(b64std(epochKey)) // distinct from E_e (HKDF, not identity)
  })

  it('wraps K_name to the invitee — only their identity private unwraps it, back to the same K_name', () => {
    const epochKey = v1.randomBytes(32)
    const nameKey = deriveGroupNameKey(epochKey)
    const sealed = wrapNameKeyToInvitee(nameKey, recipientPubB64)

    const opened = v1.asymUnwrap(
      v1.hybridSecret(recipient.priv), v1.hybridPublic(recipient.priv), fromB64std(sealed), GROUP_NAME_KEY,
    )
    expect(b64std(opened)).toBe(b64std(nameKey))
  })

  it('is domain-separated from scope-grant — a name-key wrap can never open as a key-grant', () => {
    const sealed = wrapNameKeyToInvitee(deriveGroupNameKey(v1.randomBytes(32)), recipientPubB64)
    expect(() =>
      v1.asymUnwrap(
        v1.hybridSecret(recipient.priv), v1.hybridPublic(recipient.priv), fromB64std(sealed), SCOPE_GRANT,
      ),
    ).toThrow()
  })

  // The core ADR 0027 property: a pending invitee opens the SAME canonical `encryptedName` a member reads — there
  // is no separate owner-typed name copy, so a modified owner cannot privately show a divergent name.
  it('an invitee reads the same encryptedName as a member (no divergence)', () => {
    vaultPriv = cloneRecipientPriv
    const epochKey = v1.randomBytes(32) // E_e — the name's content-epoch key a member holds
    const name = 'Familie Raddatz'

    // What the owner/members store: the name sealed under K_name derived from E_e (the canonical `encryptedName`).
    const encryptedName = sealName(deriveGroupNameKey(epochKey), name)

    // A member derives K_name from E_e and reads the canonical blob.
    const memberView = openName(deriveGroupNameKey(epochKey), encryptedName)
    // The invitee is handed K_name (wrapped) and opens the SAME blob.
    const sealedNameKey = wrapNameKeyToInvitee(deriveGroupNameKey(epochKey), recipientPubB64)
    const inviteeView = openInviteeName(sealedNameKey, encryptedName)

    expect(memberView).toBe(name)
    expect(inviteeView).toBe(name)
    expect(inviteeView).toBe(memberView) // identical — the invitee cannot be shown a different name
  })

  it('openInviteeName returns null when the vault is locked', () => {
    vaultPriv = () => null
    const epochKey = v1.randomBytes(32)
    const encryptedName = sealName(deriveGroupNameKey(epochKey), 'Buchclub')
    const sealedNameKey = wrapNameKeyToInvitee(deriveGroupNameKey(epochKey), recipientPubB64)
    expect(openInviteeName(sealedNameKey, encryptedName)).toBeNull()
  })

  it('openInviteeName returns null for a wrong-recipient / corrupt name-key (non-fatal)', () => {
    vaultPriv = cloneRecipientPriv
    const epochKey = v1.randomBytes(32)
    const encryptedName = sealName(deriveGroupNameKey(epochKey), 'Fremd')
    // A name-key wrapped to a DIFFERENT identity won't open with the recipient's key → null, not a throw.
    const other = v1.generateIdentity()
    const sealedNameKey = wrapNameKeyToInvitee(deriveGroupNameKey(epochKey), b64std(v1.serializeIdentityPublic(other.pub)))
    expect(openInviteeName(sealedNameKey, encryptedName)).toBeNull()
  })
})
