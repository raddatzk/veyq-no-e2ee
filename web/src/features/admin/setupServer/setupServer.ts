// uc.admin.setup-server (web) — cold E2EE registration of the first Server Owner on the operator-blind
// (Model B) data layer. ALL key material is born here; the server only ever receives ciphertext + public
// keys + low-sensitivity metadata. Unlike the legacy Model-A flow, the raw password is NEVER sent: the
// client derives an Ed25519 auth keypair (ADR 0003) and registers only `authPublicKey`.
import * as v1 from '../../../shared/crypto/v1'
import { b64std } from '../../../shared/base64'
import { setupApi } from '../../../api/client'
import {
  type CreateServerOwnerRequest,
  CreateServerOwnerRequestSuiteIdEnum,
  MasterKeyWrapMethodEnum,
} from '../../../api/generated'

// Argon2id params (t=3, m=32 MiB) — carried in each wrap's kdfParams for agility (a later cost bump never
// strands an account minted under lighter params).
const ARGON = v1.ARGON

export type EmergencyKit = { secretKey: string }

/** Build the request (keys born here). Returns the request + the Emergency Kit to show the user. */
export function buildCreateServerOwnerRequest(
  email: string,
  displayName: string,
  password: string,
  instanceName: string,
): { request: CreateServerOwnerRequest; emergencyKit: EmergencyKit } {
  const id = v1.generateIdentity()
  const masterKey = v1.randomBytes(32)

  // wrap the identity private bundle under the master key
  const wrappedPriv = v1.symWrap(masterKey, v1.serializeIdentityPrivate(id), 'identity-priv', v1.randomBytes(12))

  // PASSWORD wrap: KEK = Argon2id(secretKey + password). The Secret Key is the recovery secret shown to the
  // user; the password stays only in their head. Both are needed to open this wrap (2SKD).
  const secretKey = v1.randomBytes(32)
  const salt = v1.randomBytes(16)
  const kek = v1.kekPasswordSecret(secretKey, password, salt, ARGON)
  const masterWrap = v1.symWrap(kek, masterKey, 'master-key', v1.randomBytes(12))

  // RECOVERY wrap: the master key under the Secret Key ALONE (Argon2id(secretKey)). Released only via the
  // email-token-gated password reset, so recovery needs Secret Key AND email access.
  const recSalt = v1.randomBytes(16)
  const recWrap = v1.symWrap(v1.kekRecovery(secretKey, recSalt, ARGON), masterKey, 'master-key', v1.randomBytes(12))

  // Operator-blind auth (ADR 0003 / Model B): derive the Ed25519 auth keypair from password + per-account
  // salt; register only the PUBLIC key. The server never sees the password and stores no credential.
  const authSalt = v1.deriveAuthSalt(email)
  const auth = v1.deriveAuthKeypair(password, authSalt, ARGON)

  const request: CreateServerOwnerRequest = {
    email,
    displayName,
    instanceName,
    suiteId: CreateServerOwnerRequestSuiteIdEnum.V1,
    identityPublicKey: b64std(v1.serializeIdentityPublic(id.pub)),
    wrappedIdentityPrivateKey: b64std(wrappedPriv),
    authPublicKey: b64std(auth.publicKey),
    masterKeyWraps: [
      {
        method: MasterKeyWrapMethodEnum.Password,
        wrappedKey: b64std(masterWrap),
        kdfParams: b64std(v1.encodeKdfParams(ARGON, salt)),
      },
      {
        method: MasterKeyWrapMethodEnum.Recovery,
        wrappedKey: b64std(recWrap),
        kdfParams: b64std(v1.encodeKdfParams(ARGON, recSalt)),
      },
    ],
    // commitment to the master key — lets password reset prove Secret-Key possession (the RECOVERY wrap above)
    recoveryMasterKeyTag: b64std(v1.recoveryAuthTag(masterKey)),
  }
  return { request, emergencyKit: { secretKey: b64std(secretKey) } }
}

export async function registerServerOwner(
  email: string,
  displayName: string,
  password: string,
  instanceName: string,
): Promise<{ userId: string; emergencyKit: EmergencyKit }> {
  const { request, emergencyKit } = buildCreateServerOwnerRequest(email, displayName, password, instanceName)
  const result = await setupApi().createServerOwner({ createServerOwnerRequest: request })
  return { userId: result.userId, emergencyKit }
}
