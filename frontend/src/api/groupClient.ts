import { generatedCall, groupsApi } from './generatedClient'
import type {
  GroupSummary,
  SimplifiedGroupSummary,
  UpdateGroupRequest
} from './generated'

export type TenantGroup = SimplifiedGroupSummary
export type {
  GroupMemberSummary,
  GroupRole,
  GroupSummary,
  UpdateGroupRequest
} from './generated'

export async function groups(tenantName: string): Promise<TenantGroup[]> {
  const response = await generatedCall(groupsApi.getGroups({ tenantName }))
  return response.groups
}

export async function getGroup(tenantName: string, groupName: string): Promise<GroupSummary> {
  return generatedCall(groupsApi.getGroup({ tenantName, groupName }))
}

export async function createGroup(
  tenantName: string,
  name: string,
  displayName: string
): Promise<GroupSummary> {
  return generatedCall(
    groupsApi.createGroup({ tenantName, createGroupRequest: { name, displayName } })
  )
}

export async function updateGroup(
  tenantName: string,
  groupName: string,
  request: UpdateGroupRequest
): Promise<GroupSummary> {
  return generatedCall(
    groupsApi.updateGroup({ tenantName, groupName, updateGroupRequest: request })
  )
}

export async function deleteGroup(tenantName: string, groupName: string): Promise<void> {
  return generatedCall(groupsApi.deleteGroup({ tenantName, groupName }))
}
