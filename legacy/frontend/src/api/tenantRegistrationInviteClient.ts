import { userFromSession, type LoginResponse } from './authClient'
import { generatedCall, tenantRegistrationInvitesApi } from './generatedClient'
import type {
  TenantRegistrationInviteCreatedResponse,
  TenantRegistrationInviteMetadataResponse,
  TenantRegistrationInviteSummary
} from './generated'
import { createPasskeyCredential } from '../shared/utils/passkeys'

export type {
  TenantRegistrationInviteCreatedResponse,
  TenantRegistrationInviteMetadataResponse,
  TenantRegistrationInviteSummary
}

export async function createTenantRegistrationInvite(
  tenantName: string,
  expiresInDays: number,
  maxRegistrations?: number
): Promise<TenantRegistrationInviteCreatedResponse> {
  return generatedCall(
    tenantRegistrationInvitesApi.createTenantRegistrationInvite({
      tenantName,
      createTenantRegistrationInviteRequest: { expiresInDays, maxRegistrations }
    })
  )
}

export async function listTenantRegistrationInvites(
  tenantName: string
): Promise<TenantRegistrationInviteSummary[]> {
  const response = await generatedCall(
    tenantRegistrationInvitesApi.listTenantRegistrationInvites({ tenantName })
  )
  return response.invites
}

export async function revokeTenantRegistrationInvite(
  tenantName: string,
  inviteId: number
): Promise<TenantRegistrationInviteSummary> {
  return generatedCall(
    tenantRegistrationInvitesApi.revokeTenantRegistrationInvite({ tenantName, inviteId })
  )
}

export async function getTenantRegistrationInvite(
  token: string
): Promise<TenantRegistrationInviteMetadataResponse> {
  return generatedCall(tenantRegistrationInvitesApi.getTenantRegistrationInvite({ token }))
}

export async function joinTenantRegistrationInvite(
  token: string,
  email: string,
  displayName?: string,
  password?: string
): Promise<LoginResponse> {
  return userFromSession(
    await generatedCall(
      tenantRegistrationInvitesApi.joinTenantRegistrationInvite({
        token,
        joinTenantRegistrationInviteRequest: { email, displayName, password }
      })
    )
  )
}

export async function joinTenantRegistrationInviteWithPasskey(
  token: string,
  email: string,
  displayName: string
): Promise<LoginResponse> {
  const options = await generatedCall(
    tenantRegistrationInvitesApi.createTenantRegistrationInvitePasskeyOptions({
      token,
      tenantRegistrationInvitePasskeyOptionsRequest: { email, displayName }
    })
  )
  const credential = await createPasskeyCredential(options)
  return userFromSession(
    await generatedCall(
      tenantRegistrationInvitesApi.joinTenantRegistrationInviteWithPasskey({
        token,
        joinTenantRegistrationInviteWithPasskeyRequest: { email, displayName, credential }
      })
    )
  )
}
