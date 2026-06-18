// @vitest-environment node
// Local smoke seed for an already-set-up dev backend. Opt in via VEYQ_SMOKE_SEED=1.
import { describe, it } from 'vitest'
import { writeFileSync } from 'node:fs'
import { apiError } from './shared/apiError'
import { buildCreateServerOwnerRequest } from './features/admin/setupServer/setupServer'
import { login } from './features/account/login/login'
import { seedSmokeContent } from './smokeSeedData'

const apiBase = process.env.VEYQ_API_BASE ?? 'http://localhost:8080'
const seedPath = process.env.VEYQ_SEED_PATH ?? '/tmp/veyq-seed.json'
const run = process.env.VEYQ_SMOKE_SEED === '1' ? describe : describe.skip

run('local smoke seed', () => {
  it('bootstraps a dev smoke account and seeds encrypted memory content', async () => {
    const email = process.env.VEYQ_SMOKE_EMAIL ?? `codex-smoke-${Date.now()}@veyq.local`
    const password = process.env.VEYQ_SMOKE_PASSWORD ?? 'correct horse battery staple'
    const displayName = process.env.VEYQ_SMOKE_NAME ?? 'Codex Smoke'
    const { request, emergencyKit } = buildCreateServerOwnerRequest(email, displayName, password)

    const created = await fetch(`${apiBase}/api/dev/smoke/accounts`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify(request),
    })
    if (!created.ok) throw await apiError(created, 'dev smoke account')

    const session = await login(apiBase, email, password, emergencyKit.secretKey)
    const seeded = await seedSmokeContent(apiBase, session)
    writeFileSync(seedPath, JSON.stringify({ email, password, secretKey: emergencyKit.secretKey, memory: seeded.memory }))
  }, 120000)
})
