import {
  authApi,
  generatedCall,
  serverApi,
  settingsApi,
  tenantsApi,
  usersApi
} from '../../../api/generatedClient'
import type {
  ChangePasswordRequest,
  CreateManagedUserRequest,
  DeleteMyAccountRequest,
  ManagedUserSummary,
  ManagedUsersSummary,
  PasskeySummary,
  PasskeysSummary,
  CapabilityNotification,
  ServerNotification,
  ServerRole,
  ServerSettingsResponse,
  ServerUpdateEvent,
  SettingsResponse,
  TenantSettingsResponse,
  TenantRole,
  TenantUserSummary,
  TotpEnrollmentResponse,
  UpdateTenantSettingsRequest,
  UpdateUserSettingsRequest,
  UserSettingsResponse
} from './types'
import { createPasskeyCredential, getPasskeyCredential } from '../../../shared/utils/passkeys'

export function getSettings(path: string): Promise<SettingsResponse> {
  return generatedCall(settingsApi.getSettings({ path }))
}

export function getServerSettings(): Promise<ServerSettingsResponse> {
  return generatedCall(serverApi.getServerSettings())
}

export function getServerUpdateEvents(): Promise<ServerUpdateEvent[]> {
  return generatedCall(serverApi.listServerUpdateEvents()).then((response) => response.events)
}

export function getServerNotifications(): Promise<ServerNotification[]> {
  return generatedCall(serverApi.listServerNotifications()).then((response) => response.notifications)
}

export function deleteServerNotification(notificationId: number): Promise<void> {
  return generatedCall(serverApi.deleteServerNotification({ notificationId }))
}

export function getCapabilityNotifications(): Promise<CapabilityNotification[]> {
  return generatedCall(serverApi.listCapabilityNotifications()).then((response) => response.notifications)
}

export function dismissCapabilityNotification(capabilityId: string): Promise<void> {
  return generatedCall(serverApi.dismissCapabilityNotification({ capabilityId }))
}

export function getMySettings(): Promise<UserSettingsResponse> {
  return generatedCall(usersApi.me())
}

export function updateMySettings(request: UpdateUserSettingsRequest): Promise<UserSettingsResponse> {
  return generatedCall(usersApi.updateMySettings({ updateUserSettingsRequest: request }))
}

export function updateMyAvatar(file: Blob): Promise<UserSettingsResponse> {
  return generatedCall(usersApi.updateMyAvatar({ file }))
}

export function deleteMyAvatar(): Promise<UserSettingsResponse> {
  return generatedCall(usersApi.deleteMyAvatar())
}

export function confirmMyEmailChange(token: string): Promise<UserSettingsResponse> {
  return generatedCall(usersApi.confirmMyEmailChange({ confirmEmailChangeRequest: { token } }))
}

export function changeMyPassword(request: ChangePasswordRequest): Promise<void> {
  return generatedCall(usersApi.changeMyPassword({ changePasswordRequest: request }))
}

export function deleteMyPassword(password: string): Promise<UserSettingsResponse> {
  return generatedCall(usersApi.deleteMyPassword({ deleteMyPasswordRequest: { password } }))
}

export function createTotpEnrollment(): Promise<TotpEnrollmentResponse> {
  return generatedCall(authApi.createTotpEnrollment())
}

export function enableTotp(password: string, secret: string, code: string): Promise<UserSettingsResponse> {
  return generatedCall(authApi.enableTotp({ enableTotpRequest: { password, secret, code } }))
}

export function disableTotp(password: string, code: string): Promise<UserSettingsResponse> {
  return generatedCall(authApi.disableTotp({ disableTotpRequest: { password, code } }))
}

export function listMyPasskeys(): Promise<PasskeysSummary> {
  return generatedCall(usersApi.listMyPasskeys())
}

export async function createMyPasskey(): Promise<PasskeySummary> {
  const options = await generatedCall(usersApi.createMyPasskeyOptions())
  const credential = await createPasskeyCredential(options)
  return generatedCall(usersApi.createMyPasskey({ createPasskeyRequest: { credential } }))
}

export function updateMyPasskey(passkeyId: number, displayName: string): Promise<PasskeySummary> {
  return generatedCall(usersApi.updateMyPasskey({ passkeyId, updatePasskeyRequest: { displayName } }))
}

export async function deleteMyPasskeyWithPasskey(passkeyId: number): Promise<UserSettingsResponse> {
  const options = await generatedCall(authApi.passkeyLoginOptions())
  const passkey = await getPasskeyCredential(options)
  return generatedCall(usersApi.deleteMyPasskey({ passkeyId, deleteMyPasskeyRequest: { passkey } }))
}

export function deleteMyAccountWithPassword(password: string): Promise<void> {
  return deleteMyAccount({ password })
}

export async function deleteMyAccountWithPasskey(): Promise<void> {
  const options = await generatedCall(authApi.passkeyLoginOptions())
  const passkey = await getPasskeyCredential(options)
  return deleteMyAccount({ passkey })
}

function deleteMyAccount(request: DeleteMyAccountRequest): Promise<void> {
  return generatedCall(usersApi.deleteMyAccount({ deleteMyAccountRequest: request }))
}

export function getManagedUsers(): Promise<ManagedUsersSummary> {
  return generatedCall(usersApi.getAllUsers())
}

export function createManagedUser(request: CreateManagedUserRequest): Promise<ManagedUserSummary> {
  return generatedCall(usersApi.createManagedUser({ createManagedUserRequest: request }))
}

export function updateManagedUserRole(
  userId: number,
  serverRole: ServerRole
): Promise<ManagedUserSummary> {
  return generatedCall(
    usersApi.updateManagedUser({ userId, updateManagedUserRoleRequest: { serverRole } })
  ).then((summary) => findManagedUser(summary, userId))
}

export function disableManagedUser(userId: number): Promise<ManagedUserSummary> {
  return generatedCall(usersApi.disableManagedUser({ userId }))
}

export function enableManagedUser(userId: number): Promise<ManagedUserSummary> {
  return generatedCall(usersApi.enableManagedUser({ userId }))
}

export function getTenantUsers(tenantName: string): Promise<TenantUserSummary[]> {
  return generatedCall(tenantsApi.tenantUsers({ tenantName })).then((summary) => summary.users)
}

export function getTenantSettings(tenantName: string): Promise<TenantSettingsResponse> {
  return generatedCall(tenantsApi.getTenantSettings({ tenantName }))
}

export function updateTenantSettings(
  tenantName: string,
  request: UpdateTenantSettingsRequest
): Promise<TenantSettingsResponse> {
  return generatedCall(
    tenantsApi.updateTenantSettings({ tenantName, updateTenantSettingsRequest: request })
  )
}

export function updateTenantLogo(tenantName: string, file: Blob): Promise<TenantSettingsResponse> {
  return generatedCall(tenantsApi.updateTenantLogo({ tenantName, file }))
}

export function deleteTenantLogo(tenantName: string): Promise<TenantSettingsResponse> {
  return generatedCall(tenantsApi.deleteTenantLogo({ tenantName }))
}

export function inviteTenantUser(
  tenantName: string,
  email: string,
  role: TenantRole
): Promise<TenantUserSummary> {
  return generatedCall(
    tenantsApi.inviteTenantUser({ tenantName, inviteTenantUserRequest: { email, role } })
  )
}

export function reinviteTenantUser(tenantName: string, userId: number): Promise<TenantUserSummary> {
  return generatedCall(tenantsApi.reinviteTenantUser({ tenantName, userId }))
}

export function removeTenantUser(tenantName: string, userId: number): Promise<void> {
  return generatedCall(tenantsApi.removeTenantUser({ tenantName, userId }))
}

export function updateTenantUserRole(
  tenantName: string,
  userId: number,
  role: TenantRole
): Promise<TenantUserSummary> {
  return generatedCall(
    tenantsApi.updateTenantUserRole({
      tenantName,
      userId,
      updateTenantUserRoleRequest: { role }
    })
  )
}

function findManagedUser(summary: ManagedUsersSummary, userId: number): ManagedUserSummary {
  const user = summary.users.find((item) => item.userId === userId)
  if (!user) {
    throw new Error('Aktualisierter User fehlt in der API-Antwort.')
  }
  return user
}
