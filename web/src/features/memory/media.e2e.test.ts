import { readFileSync } from 'node:fs'
import { describe, it, expect } from 'vitest'
import { session } from '../../app/session'
import { login } from '../account/login/login'
import { unlockMasterKey } from '../account/unlock/unlock'
import { ensurePersonalScope, listMyScopes, openPersonalScope } from '../spaces/scopes'
import { createMemory } from './tree'
import { addMedia, listMedia, openOriginal } from './media'
import { ScopeKind } from '../../api/generated/scopes'

/**
 * Opt-in web byte-transport e2e (cr-0028, ADR 0013) — the one path the mocked media.test.ts cannot cover:
 * real `fetch` PUT/GET against **real presigned URLs** (and the store's CORS) on a **live instance** with a
 * bundled MinIO (`directTransport: ok`). Gated on `VEYQ_E2E=1`; default `npm run check` skips it.
 *
 *   docker compose -f infra/docker-compose.yml --profile s3 up -d   # backend + store behind Traefik
 *   VEYQ_E2E=1 VEYQ_BASE=http://localhost:8080 VEYQ_EMAIL=… VEYQ_PASSWORD=… VEYQ_SECRET=… npx vitest run media.e2e
 *
 * Credentials fall back to `web/scripts/.seed/dev-seed.json` (the 127.0.0.1 tenant owner from `npm run
 * seed:dev`, cr-0046), so after a `seed:dev` you can just `VEYQ_E2E=1 npx vitest run media.e2e`.
 *
 * Drives the real flow: login → unlock → personal scope → create a Memory → addMedia → listMedia →
 * openOriginal, asserting the bytes round-trip operator-blind end-to-end.
 */
/** The 127.0.0.1 tenant owner from `npm run seed:dev` (cr-0046), or null — env vars still win when set. */
function seedCreds(): { base: string; email: string; password: string; secret: string } | null {
  try {
    const seed = JSON.parse(readFileSync(new URL('../../../scripts/.seed/dev-seed.json', import.meta.url), 'utf8'))
    const t = seed.tenants?.['127.0.0.1']
    return t ? { base: `http://${t.domain}:8080`, email: t.email, password: t.password, secret: t.secretKey } : null
  } catch {
    return null
  }
}

describe.skipIf(!process.env.VEYQ_E2E)('media e2e (live, direct-to-store)', () => {
  it('round-trips media bytes through real presigned upload + download', async () => {
    const seed = seedCreds()
    session.apiBase = process.env.VEYQ_BASE ?? seed?.base ?? 'http://localhost:8080'
    const email = process.env.VEYQ_EMAIL ?? seed?.email ?? ''
    const password = process.env.VEYQ_PASSWORD ?? seed?.password ?? ''
    const secret = process.env.VEYQ_SECRET ?? seed?.secret ?? ''

    await login(email, password)
    await unlockMasterKey(password, secret)
    await ensurePersonalScope()

    const personal = (await listMyScopes()).find((s) => s.kind === ScopeKind.Personal)
    expect(personal).toBeDefined()
    const scopeId = personal!.id
    const scopeKey = (await openPersonalScope())!
    expect(scopeKey).toBeTruthy()

    const { nodeId, memoryKey } = await createMemory(scopeId, scopeKey, `E2E media ${Date.now()}`)

    const data = new Uint8Array(300 * 1024)
    for (let i = 0; i < data.length; i++) data[i] = (i * 17 + 3) & 0xff

    // raw bytes with no MIME ⇒ the original-only path (no derivable frame); the byte transport is what this gates
    const { blobId } = await addMedia(scopeId, nodeId, memoryKey, { bytes: data, mediaType: '' })
    expect(blobId).toBeTruthy()

    const items = await listMedia(scopeId, nodeId)
    expect(items.length).toBeGreaterThan(0)
    const opened = await openOriginal(scopeId, memoryKey, items[0])
    expect(Array.from(opened)).toEqual(Array.from(data))
  })
})
