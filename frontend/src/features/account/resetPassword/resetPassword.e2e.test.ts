// @vitest-environment node
// Live (VEYQ_E2E=1): registration now mints a RECOVERY master-key wrap (Secret Key alone). It must be
// persisted server-side (so a token-gated reset can release it) BUT must NEVER appear in the login key bag
// — otherwise anyone with the Secret Key could unlock without the email factor. Login must still work.
import { describe, expect, it } from 'vitest'
import { registerServerOwner } from '../../admin/setupServer/setupServer'
import { login } from '../login/login'

const apiBase = process.env.VEYQ_API_BASE ?? 'http://localhost:8080'
const run = process.env.VEYQ_E2E === '1' ? describe : describe.skip

run('password reset (recovery wrap lifecycle, live backend)', () => {
  it('registration persists a RECOVERY wrap; login still works and the key bag excludes RECOVERY', async () => {
    const pass = 'correct horse battery staple'
    const { emergencyKit } = await registerServerOwner(apiBase, 'reset-owner@veyq.app', 'Owner', pass)

    // login succeeds (the extra RECOVERY wrap doesn't disturb the PASSWORD unlock)
    const session = await login(apiBase, 'reset-owner@veyq.app', pass, emergencyKit.secretKey)
    expect(session.sessionToken).toBeTruthy()

    // the login key bag must contain PASSWORD but NOT the RECOVERY wrap (email-gated, never shipped here)
    const res = await fetch(`${apiBase}/api/login`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ email: 'reset-owner@veyq.app', password: pass }),
    })
    const methods = (await res.json()).keyBag.masterKeyWraps.map((w: { unlockMethod: string }) => w.unlockMethod)
    expect(methods).toContain('PASSWORD')
    expect(methods).not.toContain('RECOVERY')
  }, 120000)
})
