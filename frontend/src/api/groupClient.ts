import { generatedCall, groupsApi } from './generatedClient'
import type { SimplifiedGroupSummary } from './generated/groups'

export type TenantGroup = SimplifiedGroupSummary

export async function groups(tenantName: string): Promise<TenantGroup[]> {
  const response = await generatedCall(groupsApi.getGroups({ tenantName }))
  return response.groups
}

export async function createGroup(
  tenantName: string,
  name: string,
  displayName: string
): Promise<TenantGroup> {
  return generatedCall(
    groupsApi.createGroup({ tenantName, createGroupRequest: { name, displayName } })
  )
}
