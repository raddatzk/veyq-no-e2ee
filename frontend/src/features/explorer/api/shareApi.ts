import { callApi } from '../../../shared/api/httpClient'
import { pathQuery, tenantApiPath } from '../../../shared/utils/paths'
import type { LibraryItemType, LibraryShares } from './types'

function itemSegment(type: LibraryItemType): string {
  return type === 'folder' ? 'folders' : 'albums'
}

export function getLibraryShares(
  tenantName: string,
  type: LibraryItemType,
  path: string
): Promise<LibraryShares> {
  return callApi(tenantApiPath(tenantName, itemSegment(type), `/shares?${pathQuery(path)}`))
}

export function shareLibraryItemWithUser(
  tenantName: string,
  type: LibraryItemType,
  path: string,
  userId: number
): Promise<LibraryShares> {
  return callApi(tenantApiPath(tenantName, itemSegment(type), `/shares/users?${pathQuery(path)}`), {
    method: 'POST',
    body: JSON.stringify({ userId })
  })
}

export function shareLibraryItemWithGroup(
  tenantName: string,
  type: LibraryItemType,
  path: string,
  groupId: number
): Promise<LibraryShares> {
  return callApi(
    tenantApiPath(tenantName, itemSegment(type), `/shares/groups?${pathQuery(path)}`),
    {
      method: 'POST',
      body: JSON.stringify({ groupId })
    }
  )
}

export function unshareLibraryItemWithUser(
  tenantName: string,
  type: LibraryItemType,
  path: string,
  userId: number
): Promise<LibraryShares> {
  return callApi(
    tenantApiPath(tenantName, itemSegment(type), `/shares/users/${userId}?${pathQuery(path)}`),
    { method: 'DELETE' }
  )
}

export function unshareLibraryItemWithGroup(
  tenantName: string,
  type: LibraryItemType,
  path: string,
  groupId: number
): Promise<LibraryShares> {
  return callApi(
    tenantApiPath(tenantName, itemSegment(type), `/shares/groups/${groupId}?${pathQuery(path)}`),
    { method: 'DELETE' }
  )
}
