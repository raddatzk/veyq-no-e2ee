import { callApi } from '../../../shared/api/httpClient'
import { pathQuery, tenantApiPath } from '../../../shared/utils/paths'
import type { SettingsResponse } from './types'

export function getSettings(tenantName: string, path: string): Promise<SettingsResponse> {
  return callApi(tenantApiPath(tenantName, 'settings', `?${pathQuery(path)}`))
}
