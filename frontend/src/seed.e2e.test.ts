// @vitest-environment node
// Seeds a running backend with a personal space, a folder + a memory, and several varied-aspect images
// (so the justified gallery has real content). Opt-in via VEYQ_SEED=1; writes creds to /tmp/veyq-seed.json
// for the Playwright smoke run. Uses the exact client modules the UI calls.
import { describe, it } from 'vitest'
import { writeFileSync } from 'node:fs'
import { registerServerOwner } from './features/admin/setupServer/setupServer'
import { login } from './features/account/login/login'
import { seedSmokeContent } from './smokeSeedData'

const apiBase = process.env.VEYQ_API_BASE ?? 'http://localhost:8080'
const run = process.env.VEYQ_SEED === '1' ? describe : describe.skip

run('seed', () => {
  it('seeds a personal space with a memory + varied images', async () => {
    const pass = 'correct horse battery staple'
    const { emergencyKit } = await registerServerOwner(apiBase, 'demo@veyq.app', 'Kevin', pass)
    const session = await login(apiBase, 'demo@veyq.app', pass, emergencyKit.secretKey)
    const seeded = await seedSmokeContent(apiBase, session)

    writeFileSync('/tmp/veyq-seed.json', JSON.stringify({ email: 'demo@veyq.app', password: pass, secretKey: emergencyKit.secretKey, memory: seeded.memory }))
  }, 120000)
})
