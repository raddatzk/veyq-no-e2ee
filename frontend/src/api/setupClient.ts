import { callApi } from './httpClient'

export type SetupStatusResponse = {
  adminRequired: boolean
  tenantAvailable: boolean
}

export type AdminSetupRequest = {
  password: string
  displayName: string
  email: string
}

export async function setupStatus(): Promise<SetupStatusResponse> {
  return callApi('/api/setup')
}

export async function setupAdmin(request: AdminSetupRequest): Promise<void> {
  await callApi('/api/setup/admin', {
    method: 'POST',
    body: JSON.stringify(request)
  })
}
