import { callApi } from '../../../shared/api/httpClient'
import { pathQuery, tenantApiPath } from '../../../shared/utils/paths'
import type {
  CreateLibraryItemRequest,
  ExplorerAlbum,
  ExplorerFolder,
  LibraryItemType,
  MoveLibraryItemRequest,
  UpdateLibraryItemRequest
} from './types'

function itemSegment(type: LibraryItemType): string {
  return type === 'folder' ? 'folders' : 'albums'
}

export function createFolder(
  tenantName: string,
  request: CreateLibraryItemRequest
): Promise<ExplorerFolder> {
  return callApi(tenantApiPath(tenantName, 'folders'), {
    method: 'POST',
    body: JSON.stringify(request)
  })
}

export function createAlbum(
  tenantName: string,
  request: CreateLibraryItemRequest
): Promise<ExplorerAlbum> {
  return callApi(tenantApiPath(tenantName, 'albums'), {
    method: 'POST',
    body: JSON.stringify(request)
  })
}

export function updateLibraryItem(
  tenantName: string,
  type: LibraryItemType,
  request: UpdateLibraryItemRequest
): Promise<ExplorerFolder | ExplorerAlbum> {
  return callApi(tenantApiPath(tenantName, itemSegment(type), '/update'), {
    method: 'POST',
    body: JSON.stringify(request)
  })
}

export function moveLibraryItem(
  tenantName: string,
  type: LibraryItemType,
  request: MoveLibraryItemRequest
): Promise<ExplorerFolder | ExplorerAlbum> {
  return callApi(tenantApiPath(tenantName, itemSegment(type), '/move'), {
    method: 'POST',
    body: JSON.stringify(request)
  })
}

export function deleteLibraryItem(
  tenantName: string,
  type: LibraryItemType,
  path: string
): Promise<void> {
  return callApi(tenantApiPath(tenantName, itemSegment(type), `?${pathQuery(path)}`), {
    method: 'DELETE'
  })
}

export function setAlbumCover(
  tenantName: string,
  albumPath: string,
  mediaId: string,
  hidden: boolean
): Promise<ExplorerAlbum> {
  return callApi(tenantApiPath(tenantName, 'albums', '/cover'), {
    method: 'POST',
    body: JSON.stringify({ albumPath, mediaId, hidden })
  })
}
