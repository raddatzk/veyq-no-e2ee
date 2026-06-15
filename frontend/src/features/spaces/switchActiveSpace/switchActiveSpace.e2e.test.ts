// @vitest-environment node
import { describe, it, expect } from 'vitest'
import { registerServerOwner } from '../../admin/setupServer/setupServer'
import { login } from '../../account/login/login'
import { createTenant, issueOwnerInvite, claimTenantOwnership } from '../../admin/createTenantInviteOwner/tenant'
import { getOrCreateContentKey, writeItem, readItems } from '../../content/scopeContent'
import { listMySpaces, openSpace } from './switchActiveSpace'

const env = (globalThis as { process?: { env?: Record<string, string | undefined> } }).process?.env ?? {}
const apiBase = env.VEYQ_API_BASE ?? 'http://localhost:8080'
const suite = env.VEYQ_E2E === '1' ? describe : describe.skip

suite('switch-active-space E2E (web -> live backend)', () => {
  it('lists personal + group spaces and switches between them with already-held keys', async () => {
    const pass = 'correct horse battery staple'
    const { emergencyKit } = await registerServerOwner(apiBase, 'admin@example.com', 'Admin', pass)
    const a = await login(apiBase, 'admin@example.com', pass, emergencyKit.secretKey)
    const t1 = await createTenant(apiBase, a.sessionToken, 'acme', 'Acme Group')
    const inv = await issueOwnerInvite(apiBase, a.sessionToken, t1.id)
    await claimTenantOwnership(apiBase, a.sessionToken, inv.inviteId, inv.bootstrapSecret, a.identityPublicKey.kemPublicKey)

    // the switcher lists both spaces: the personal one + the tenant just claimed
    const spaces = await listMySpaces(apiBase, a)
    const personal = spaces.find((s) => s.kind === 'PERSONAL')
    const group = spaces.find((s) => s.kind === 'TENANT')
    expect(personal?.displayName).toBe('Admin')
    expect(group?.role).toBe('Owner')
    expect(group?.tenantId).toBe(t1.id)

    // switch into the personal space and write there (keys already held — no secrets fetched)
    const pScope = await openSpace(apiBase, a, personal!)
    const pCk = await getOrCreateContentKey(apiBase, a, pScope)
    await writeItem(apiBase, a, pScope, pCk, 'personal note')

    // switch into the group space and write there
    const gScope = await openSpace(apiBase, a, group!)
    const gCk = await getOrCreateContentKey(apiBase, a, gScope)
    await writeItem(apiBase, a, gScope, gCk, 'group note')

    // each space sees only its own content — switching truly changes the active scope context
    const pItems = await readItems(apiBase, a, pScope, pCk)
    const gItems = await readItems(apiBase, a, gScope, gCk)
    expect(pItems).toContain('personal note')
    expect(pItems).not.toContain('group note')
    expect(gItems).toContain('group note')
    expect(gItems).not.toContain('personal note')
  })
})
