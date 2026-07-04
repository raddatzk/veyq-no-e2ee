// The web worker loop for the operator-blind background-task mechanism (ADR 0021, cr-0076) — its first
// consumer, grant-delivery (ADR 0020). Any online key-holder is an equal worker: it pulls grant-delivery jobs
// for a scope it has opened, wraps that scope's key `K_SC` to a pending member's *foreign* identity public key
// (`asymWrap(hybridPublic(target), K_SC, "scope-grant")` — the first wrap to a foreign identity in the client),
// and completes; the backend writes the opaque ciphertext onto the target membership, idempotently. Delivery
// is automatic and first-online-wins — the wrap runs on-device, the server only ever relays ciphertext.
import * as v1 from '../../shared/crypto/v1'
import { b64std, fromB64std } from '../../shared/base64'
import { tasksApi } from '../../api/client'
import { withRefresh } from '../account/login/login'
import { SCOPE_GRANT } from '../spaces/scopes'
import { GRANT_DELIVERY } from './capabilityTags'
import type { ClaimedJob } from '../../api/generated/tasks'

/** Just what the worker needs from a cr-0075 opened scope: its id + the in-memory scope key `K_SC`. */
export type ScopeKeyHolder = { scopeId: string; key: Uint8Array }

/**
 * Wrap a scope key to another member's **published** identity public key — the one new crypto move (ADR 0020):
 * the same hybrid KEM + context as a self-grant, only the recipient pubkey is someone else's. Returns the
 * opaque base64 ciphertext to post as that member's `wrappedGrant`; the target opens it with their own identity
 * private on next entry (the unchanged self-unwrap path).
 */
export function wrapScopeKeyToMember(scopeKey: Uint8Array, targetIdentityPublicKeyB64: string): string {
  const pub = v1.deserializeIdentityPublic(fromB64std(targetIdentityPublicKeyB64))
  const hybridPub: v1.HybridPublic = { x: pub.kemX, m: pub.kemM }
  return b64std(v1.asymWrap(hybridPub, scopeKey, SCOPE_GRANT, { nonce: v1.randomBytes(12) }))
}

/**
 * Drain the grant-delivery jobs claimable for a single opened scope: claim → wrap `K_SC` to the target →
 * complete, until nothing is left (a 204 → null claim). Idempotent + first-online-wins by construction — the
 * backend leases each job to one worker and no-ops a completion whose target is already granted, so a second
 * key-holder running this simply finds nothing to claim. Returns how many grants this worker delivered.
 */
export async function runGrantDeliveryWorker(scope: ScopeKeyHolder): Promise<number> {
  let delivered = 0
  for (;;) {
    const job = await claimGrantDelivery(scope.scopeId)
    if (!job) break
    const target = job.grantDelivery
    if (job.capabilityTag !== GRANT_DELIVERY || !target) {
      // Not a grant-delivery job we can fulfil with this scope key — release it for the right worker.
      await failJob(job.jobId, 'not-grant-delivery')
      continue
    }
    const wrap = wrapScopeKeyToMember(scope.key, target.identityPublicKey)
    await completeJob(job.jobId, wrap)
    delivered++
  }
  return delivered
}

async function claimGrantDelivery(scopeId: string): Promise<ClaimedJob | null> {
  const job = await withRefresh(() =>
    tasksApi().claimTask({ claimRequest: { capabilityTags: [GRANT_DELIVERY], scopeId } }),
  )
  return job ?? null
}

async function completeJob(jobId: string, sealedResult: string): Promise<void> {
  await withRefresh(() => tasksApi().completeTask({ jobId, completeRequest: { sealedResult } }))
}

async function failJob(jobId: string, reason: string): Promise<void> {
  await withRefresh(() => tasksApi().failTask({ jobId, failRequest: { reason } }))
}
