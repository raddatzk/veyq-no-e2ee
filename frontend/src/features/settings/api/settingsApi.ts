import { callApi } from '../../../shared/api/httpClient'
import { pathQuery } from '../../../shared/utils/paths'
import type {
  ChangePasswordRequest,
  ServerSettingsResponse,
  SettingsResponse,
  UpdateUserSettingsRequest,
  UserSettingsResponse
} from './types'

export function getSettings(path: string): Promise<SettingsResponse> {
  return callApi(`/api/settings?${pathQuery(path)}`)
}

export function getServerSettings(): Promise<ServerSettingsResponse> {
  return callApi('/api/settings/server')
}

export function getMySettings(): Promise<UserSettingsResponse> {
  return callApi('/api/settings/me')
}

export function updateMySettings(request: UpdateUserSettingsRequest): Promise<UserSettingsResponse> {
  return callApi('/api/settings/me', {
    method: 'PUT',
    body: JSON.stringify(request)
  })
}

export function changeMyPassword(request: ChangePasswordRequest): Promise<void> {
  return callApi('/api/settings/me/password', {
    method: 'POST',
    body: JSON.stringify(request)
  })
}
