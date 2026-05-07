import {
  generatedCall,
  serverApi,
  settingsApi,
  tenantsApi,
  usersApi
} from '../../../api/generatedClient'
import type {
  ChangePasswordRequest,
  CreateManagedUserRequest,
  ManagedUserSummary,
  ManagedUsersSummary,
  ServerRole,
  ServerSettingsResponse,
  SettingsResponse,
  TenantRole,
  TenantUserSummary,
  UpdateUserSettingsRequest,
  UserSettingsResponse
} from './types'

export function getSettings(path: string): Promise<SettingsResponse> {
  return generatedCall(settingsApi.getSettings({ path }))
}

export function getServerSettings(): Promise<ServerSettingsResponse> {
  return generatedCall(serverApi.getServerSettings())
}

export function getMySettings(): Promise<UserSettingsResponse> {
  return generatedCall(usersApi.me())
}

export function updateMySettings(request: UpdateUserSettingsRequest): Promise<UserSettingsResponse> {
  return generatedCall(usersApi.updateMySettings({ updateUserSettingsRequest: request }))
}

export function confirmMyEmailChange(token: string): Promise<UserSettingsResponse> {
  return generatedCall(usersApi.confirmMyEmailChange({ confirmEmailChangeRequest: { token } }))
}

export function changeMyPassword(request: ChangePasswordRequest): Promise<void> {
  return generatedCall(usersApi.changeMyPassword({ changePasswordRequest: request }))
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

export function getTenantUsers(tenantName: string): Promise<TenantUserSummary[]> {
  return generatedCall(tenantsApi.tenantUsers({ tenantName })).then((summary) => summary.users)
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
