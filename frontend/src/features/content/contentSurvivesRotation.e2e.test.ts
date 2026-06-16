// @vitest-environment node
import { describe, it, expect } from 'vitest'
import { registerServerOwner } from '../admin/setupServer/setupServer'
import { login } from '../account/login/login'
import { createTenant, issueOwnerInvite, claimTenantOwnership } from '../admin/createTenantInviteOwner/tenant'
import { issueMemberInvite, registerViaMemberInvite, rotateScope } from '../admin/inviteMembersRoles/inviteMembers'
import { openTenantScope, getOrCreateContentKey, writeItem, readItems } from './scopeContent'
import { createNode, listNodes, decodeNode, slugTokenFor } from '../memory/createTree/memoryTree'

const env = (globalThis as { process?: { env?: Record<string, string | undefined> } }).process?.env ?? {}
const apiBase = env.VEYQ_API_BASE ?? 'http://localhost:8080'
const suite = env.VEYQ_E2E === '1' ? describe : describe.skip

suite('content survives scope rotation E2E (web -> live backend)', () => {
  it('re-wraps the content key on rotation; content stays readable and new members can read it', async () => {
    // owner A claims T1 and writes encrypted content under its (tenant) scope
    const aPass = 'correct horse battery staple'
    const { emergencyKit: aKit } = await registerServerOwner(apiBase, 'admin@example.com', 'Admin', aPass)
    const a = await login(apiBase, 'admin@example.com', aPass, aKit.secretKey)
    const t1 = await createTenant(apiBase, a.sessionToken, 'acme', 'Acme', 'acme.example')
    const inv = await issueOwnerInvite(apiBase, a.sessionToken, t1.id)
    await claimTenantOwnership(apiBase, a.sessionToken, inv.inviteId, inv.bootstrapSecret, a.identityPublicKey.kemPublicKey)

    const scopeBefore = await openTenantScope(apiBase, a, t1.id)
    const ckBefore = await getOrCreateContentKey(apiBase, a, scopeBefore)
    await writeItem(apiBase, a, scopeBefore, ckBefore, 'trip notes')
    // also a Memory-tree node (name + slug sealed under the content key), to prove the tree survives rotation
    const nodeBefore = await createNode(apiBase, a, scopeBefore.scopeId, ckBefore, { type: 'FOLDER', name: 'Urlaube', slug: 'urlaube', orderIndex: 0 })

    // member B joins T1 via a temp-wrap invite
    const bPass = 'a whole new passphrase entirely'
    const memberInvite = await issueMemberInvite(apiBase, a, t1.id, 'Member')
    const bReg = await registerViaMemberInvite(apiBase, memberInvite.inviteId, memberInvite.bootstrapSecret, memberInvite.tempPrivateKey, 'b@example.com', 'B', bPass)
    const b = await login(apiBase, 'b@example.com', bPass, bReg.emergencyKit.secretKey)

    // A rotates the scope (new keypair) — a FRESH content key is minted and every item re-sealed under it
    await rotateScope(apiBase, a, t1.id)

    // A still reads the pre-rotation content under the freshly-granted new keypair + fresh content key
    const aScope = await openTenantScope(apiBase, a, t1.id)
    const aCk = await getOrCreateContentKey(apiBase, a, aScope)
    expect(await readItems(apiBase, a, aScope, aCk)).toContain('trip notes')

    // forward secrecy: the OLD content key (which a removed member could have cached) opens nothing now —
    // the item ciphertext was re-sealed under the fresh key, so the cached key no longer decrypts it
    expect(Buffer.from(aCk).equals(Buffer.from(ckBefore))).toBe(false)
    await expect(readItems(apiBase, a, aScope, ckBefore)).rejects.toThrow()

    // the Memory-tree node was re-sealed too: its name reads under the fresh key, its slug token was
    // recomputed under the fresh key, and the cached old key can no longer decrypt the node name
    const nodesAfter = await listNodes(apiBase, a, aScope.scopeId)
    const node = nodesAfter.find((n) => n.id === nodeBefore.id)!
    expect(decodeNode(aCk, node).name).toBe('Urlaube')
    expect(node.slugToken).toBe(slugTokenFor(aCk, aScope.scopeId, 'urlaube'))
    expect(node.slugToken).not.toBe(nodeBefore.slugToken)
    expect(() => decodeNode(ckBefore, node)).toThrow()

    // and B — granted only at rotation — reads content written before B even joined
    const bScope = await openTenantScope(apiBase, b, t1.id)
    const bCk = await getOrCreateContentKey(apiBase, b, bScope)
    expect(await readItems(apiBase, b, bScope, bCk)).toContain('trip notes')
  })
})
