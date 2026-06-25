import { describe, expect, it } from 'vitest'
import { apiError } from './apiError'

describe('legacy apiError', () => {
  it('does not append response bodies to UI-visible messages', async () => {
    const error = await apiError(
      new Response(
        JSON.stringify({
          details: 'jakarta.transaction.RollbackException: Could not commit transaction',
          stack: 'com.arjuna.ats.internal.jta.transaction.arjunacore.TransactionImple.commit',
        }),
        { status: 500 },
      ),
      'POST /api/tenant-invites/invite-id/claim',
    )

    expect(error.message).toBe('POST /api/tenant-invites/invite-id/claim failed: 500')
    expect(error.message).not.toContain('RollbackException')
    expect(error.message).not.toContain('stack')
  })
})
