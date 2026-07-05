import { readFileSync } from 'node:fs'
import { describe, it, expect } from 'vitest'
import { session } from '../../app/session'
import { login } from '../account/login/login'
import { unlockMasterKey } from '../account/unlock/unlock'
import { ensurePersonalScope, listMyScopes, openPersonalScope } from '../spaces/scopes'
import { epochsOf } from '../spaces/epochsTestUtil'
import { createMemory, listTree, openContentKey } from './tree'
import { setMemoryDate, unsealMemoryDate } from './memoryDate'
import { setMemoryLocation, unsealMemoryLocation, computeMedoid } from './memoryLocation'
import { reverseGeocode, defaultLevel, type GeoLevel } from './geocode'
import { fromB64std } from '../../shared/base64'
import { ScopeKind } from '../../api/generated/scopes'

/**
 * Opt-in web location e2e (cr-0069) — the live path the mocked tests can't cover: the sealed `location`
 * round-trips through the **real backend + DB** operator-blind (no plaintext name/coord crosses), a date sealed
 * on the same node **survives** a location write (read-modify-write), a location removal keeps the date, a
 * pre-armed `auto` location is born at creation, and reverse geocoding hits a **real third-party provider**.
 *
 * Gated on `VEYQ_E2E=1`; default `npm run check` skips it. Credentials fall back to the seed (cr-0046):
 *   docker compose -f infra/docker-compose.yml --profile s3 up -d && (start backend) && npm run seed:dev
 *   VEYQ_E2E=1 npx vitest run memoryLocation.e2e
 * The geocode leg additionally needs `VEYQ_GEOCODER` (e.g. https://nominatim.openstreetmap.org); without it
 * the geocode assertion is skipped (the seal legs still run).
 */
function seedCreds(): { base: string; email: string; password: string; secret: string } | null {
  try {
    const seed = JSON.parse(readFileSync(new URL('../../../scripts/.seed/dev-seed.json', import.meta.url), 'utf8'))
    const t = seed.tenants?.['127.0.0.1']
    return t ? { base: `http://${t.domain}:8080`, email: t.email, password: t.password, secret: t.secretKey } : null
  } catch {
    return null
  }
}

const TENERIFE_A = { lat: 28.2916, lng: -16.6291 }
const TENERIFE_B = { lat: 28.4682, lng: -16.2546 }

describe.skipIf(!process.env.VEYQ_E2E)('memory location e2e (live, operator-blind seal)', () => {
  it('seals a location operator-blind, preserves the date (RMW), removes it, pre-arms at creation', async () => {
    const seed = seedCreds()
    session.apiBase = process.env.VEYQ_BASE ?? seed?.base ?? 'http://localhost:8080'
    await login(process.env.VEYQ_EMAIL ?? seed?.email ?? '', process.env.VEYQ_PASSWORD ?? seed?.password ?? '')
    await unlockMasterKey(process.env.VEYQ_PASSWORD ?? seed?.password ?? '', process.env.VEYQ_SECRET ?? seed?.secret ?? '')
    await ensurePersonalScope()
    const personal = (await listMyScopes()).find((s) => s.kind === ScopeKind.Personal)!
    const scopeId = personal.id
    const scopeKey = (await openPersonalScope())!

    // --- a Memory with a date, then a location set on it ---
    const { nodeId, memoryKey } = await createMemory(scopeId, epochsOf(scopeKey), `E2E place ${Date.now()}`)
    const date = { mode: 'range' as const, from: '2026-05-01', to: '2026-05-09' }
    let meta = await setMemoryDate(scopeId, nodeId, memoryKey, date, null)

    const medoid = computeMedoid([TENERIFE_A, TENERIFE_B])!
    // real reverse geocode (optional leg) — returns the address hierarchy; we pick the city level
    let name = 'Teneriffa'
    let level: GeoLevel['key'] = 'city'
    const geocoder = process.env.VEYQ_GEOCODER
    if (geocoder) {
      // A browser supplies its own User-Agent; in node we inject a descriptive one per the Nominatim usage policy.
      const uaFetch: typeof fetch = (input, init) =>
        fetch(input, { ...init, headers: { ...(init?.headers as Record<string, string>), 'User-Agent': 'veyq-dev-smoke/1.0 (dev@veyq.test)' } })
      const levels = await reverseGeocode(geocoder, medoid.lat, medoid.lng, 'de', uaFetch)
      expect(levels && levels.length).toBeTruthy()
      const picked = defaultLevel(levels!)!
      name = picked.name
      level = picked.key
      // the veyq backend was never in the loop — a third-party call only
      console.log(`[e2e] reverse-geocoded medoid → "${name}" (${level}); levels: ${levels!.map((l) => l.name).join(' › ')}`)
    }
    meta = await setMemoryLocation(scopeId, nodeId, memoryKey, {
      name, nameSource: geocoder ? 'geocoded' : 'manual', derived: medoid, derivedMode: 'auto',
      nameAnchor: { lat: medoid.lat, lng: medoid.lng, level },
    }, meta)

    // re-fetch from the server: the location AND the date both unseal (RMW kept the sibling)
    const fetched = (await listTree(scopeId, epochsOf(scopeKey))).find((n) => n.id === nodeId)!
    const key = openContentKey(scopeKey, fetched)!
    expect(unsealMemoryLocation(key, fetched)?.name).toBe(name)
    expect(unsealMemoryDate(key, fetched)).toEqual(date)

    // operator-blind: the stored ciphertext (echoed verbatim by listNodes) carries no plaintext name/coord
    const plain = new TextDecoder('utf-8', { fatal: false }).decode(fromB64std(fetched.wrappedMeta!))
    expect(plain).not.toContain(name)
    expect(plain).not.toContain('28.') // no latitude fragment
    expect(plain).not.toContain('2026-05-01') // no date either

    // --- remove the location: place gone, date intact ---
    const removed = await setMemoryLocation(scopeId, nodeId, memoryKey, null, meta)
    expect(unsealMemoryLocation(memoryKey, { wrappedMeta: removed })).toBeNull()
    expect(unsealMemoryDate(memoryKey, { wrappedMeta: removed })).toEqual(date)

    // --- pre-arm an auto location at creation (no derived yet) ---
    const born = await createMemory(scopeId, epochsOf(scopeKey), `E2E prearm ${Date.now()}`, null, {
      name: 'Boulder gym', nameSource: 'manual', derivedMode: 'auto',
    })
    const bornNode = (await listTree(scopeId, epochsOf(scopeKey))).find((n) => n.id === born.nodeId)!
    const bornLoc = unsealMemoryLocation(born.memoryKey, bornNode)
    expect(bornLoc).toEqual({ name: 'Boulder gym', nameSource: 'manual', derivedMode: 'auto', derived: undefined, nameAnchor: undefined })
  })
})
