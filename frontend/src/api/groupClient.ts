import { callApi } from './httpClient'

export type TenantGroup = {
  id: number
  name: string
  displayName: string
}

type GroupsResponse = {
  groups: TenantGroup[]
}

export async function groups(tenantName: string): Promise<TenantGroup[]> {
  const response = await callApi<GroupsResponse>(`/api/${tenantName}/groups`)
  return response.groups
}

export async function createGroup(
  tenantName: string,
  name: string,
  displayName: string
): Promise<TenantGroup> {
  return callApi(`/api/${tenantName}/groups`, {
    method: 'POST',
    body: JSON.stringify({ name, displayName })
  })
}
