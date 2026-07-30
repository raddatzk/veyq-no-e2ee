import { describe, it, expect } from 'vitest'
import { birthInstance } from '../../test/e2eBootstrap'
import { ensurePersonalScope, listMyScopes, openPersonalScope } from '../spaces/scopes'
import { epochsOf } from '../spaces/epochsTestUtil'
import { ScopeKind } from '../../api/generated/scopes'
import { createMemory, listTree } from './tree'
import { setMemoryDate, unsealMemoryDate } from './memoryDate'
import { setMemoryLocation, unsealMemoryLocation } from './memoryLocation'
import { setMemoryCover, unsealMemoryCover, type MemoryCover } from './memoryCover'

/**
 * Opt-in live smoke for the Memory cover (cr-0072) — the one path the API-mocked unit tests cannot cover: the
 * sealed `cover` sub-object round-tripping through the **real** operator-blind backend (`updateNode` → `listNodes`)
 * beside the sibling date + location, read back from the SERVER-returned `wrappedMeta`. Gated on `VEYQ_E2E=1`;
 * default `npm run check` skips it. The instance is cold-birthed over the real API by `src/test/e2eBootstrap.ts`,
 * so this needs a FRESH instance and no seeder.
 *
 *   docker compose -f infra/docker-compose.yml --profile s3 up -d
 *   VEYQ_E2E=1 npx vitest run memoryCover.e2e
 *
 * Or `python3 scripts/run_web_e2e.py`, which is what CI runs.
 */
describe.skipIf(!process.env.VEYQ_E2E)('memory cover e2e (live, operator-blind round-trip)', () => {
  it('a sealed cover survives updateNode → listNodes beside the date + location, and remove drops only it', async () => {
    await birthInstance('E2E Cover')
    await ensurePersonalScope()
    const personal = (await listMyScopes()).find((s) => s.kind === ScopeKind.Personal)
    expect(personal).toBeDefined()
    const scopeId = personal!.id
    const scopeKey = (await openPersonalScope())!
    expect(scopeKey).toBeTruthy()

    const { nodeId, memoryKey } = await createMemory(scopeId, epochsOf(scopeKey), `Cover smoke ${Date.now()}`)

    // Read back the node's SERVER-persisted wrappedMeta (operator-blind — decrypted here under K_mem).
    const readBack = async () => {
      const node = (await listTree(scopeId, epochsOf(scopeKey))).find((n) => n.id === nodeId)
      expect(node).toBeDefined()
      return node!
    }

    // Build up the shared blob one sub-object at a time (each is a real updateNode; each chains the prior blob
    // as currentMeta, exactly as the UI's read-modify-write does).
    const date = { mode: 'range' as const, from: '2026-05-01', to: '2026-05-09' }
    const location = { name: 'Teneriffa', nameSource: 'manual' as const, derivedMode: 'fixed' as const }
    const cover: MemoryCover = { source: { kind: 'media', mediaId: 'smoke-media-id' }, crop: { x: 0.1, y: 0.2, width: 0.5, height: 0.28125 } }

    let meta = await setMemoryDate(scopeId, nodeId, memoryKey, date, null)
    meta = await setMemoryLocation(scopeId, nodeId, memoryKey, location, meta)
    await setMemoryCover(scopeId, nodeId, memoryKey, cover, meta)

    // All three sub-objects come back on the server-stored blob — the cover never clobbered the date/location.
    const withCover = await readBack()
    expect(unsealMemoryDate(memoryKey, withCover)).toEqual(date)
    expect(unsealMemoryLocation(memoryKey, withCover)).toMatchObject({ name: 'Teneriffa', derivedMode: 'fixed' })
    expect(unsealMemoryCover(memoryKey, withCover)).toEqual(cover)

    // Remove the cover (read-modify-write against the live blob) — only the cover drops; date + location remain.
    await setMemoryCover(scopeId, nodeId, memoryKey, null, withCover.wrappedMeta)
    const withoutCover = await readBack()
    expect(unsealMemoryCover(memoryKey, withoutCover)).toBeNull()
    expect(unsealMemoryDate(memoryKey, withoutCover)).toEqual(date)
    expect(unsealMemoryLocation(memoryKey, withoutCover)).toMatchObject({ name: 'Teneriffa' })
  })
})
