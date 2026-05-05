import {
  generatedCall,
  serverApi,
  settingsApi,
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

export function resendManagedUserInvitation(userId: number): Promise<ManagedUserSummary> {
  return generatedCall(usersApi.sendManagedUserInvitation({ userId }))
}

function findManagedUser(summary: ManagedUsersSummary, userId: number): ManagedUserSummary {
  const user = summary.users.find((item) => item.userId === userId)
  if (!user) {
    throw new Error('Aktualisierter User fehlt in der API-Antwort.')
  }
  return user
}
