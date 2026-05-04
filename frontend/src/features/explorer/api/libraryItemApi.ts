import { albumsApi, foldersApi, generatedCall } from '../../../api/generatedClient'
import type {
  CreateLibraryItemRequest as GeneratedCreateLibraryItemRequest,
  MoveLibraryItemRequest as GeneratedMoveLibraryItemRequest,
  UpdateLibraryItemRequest as GeneratedUpdateLibraryItemRequest
} from '../../../api/generated/library'
import type {
  CreateLibraryItemRequest,
  ExplorerAlbum,
  ExplorerFolder,
  LibraryItemType,
  MoveLibraryItemRequest,
  UpdateLibraryItemRequest
} from './types'

export function createFolder(
  tenantName: string,
  request: CreateLibraryItemRequest
): Promise<ExplorerFolder> {
  return generatedCall(
    foldersApi.createFolder({ tenantName, createLibraryItemRequest: createRequest(request) })
  ) as Promise<ExplorerFolder>
}

export function createAlbum(
  tenantName: string,
  request: CreateLibraryItemRequest
): Promise<ExplorerAlbum> {
  return generatedCall(
    albumsApi.createAlbum({ tenantName, createLibraryItemRequest: createRequest(request) })
  ) as Promise<ExplorerAlbum>
}

export function updateLibraryItem(
  tenantName: string,
  type: LibraryItemType,
  request: UpdateLibraryItemRequest
): Promise<ExplorerFolder | ExplorerAlbum> {
  if (type === 'folder') {
    return generatedCall(
      foldersApi.updateFolder({ tenantName, updateLibraryItemRequest: updateRequest(request) })
    ) as Promise<ExplorerFolder>
  }
  return generatedCall(
    albumsApi.updateAlbum({ tenantName, updateLibraryItemRequest: updateRequest(request) })
  ) as Promise<ExplorerAlbum>
}

export function moveLibraryItem(
  tenantName: string,
  type: LibraryItemType,
  request: MoveLibraryItemRequest
): Promise<ExplorerFolder | ExplorerAlbum> {
  if (type === 'folder') {
    return generatedCall(
      foldersApi.moveFolder({ tenantName, moveLibraryItemRequest: moveRequest(request) })
    ) as Promise<ExplorerFolder>
  }
  return generatedCall(
    albumsApi.moveAlbum({ tenantName, moveLibraryItemRequest: moveRequest(request) })
  ) as Promise<ExplorerAlbum>
}

export function deleteLibraryItem(
  tenantName: string,
  type: LibraryItemType,
  path: string
): Promise<void> {
  if (type === 'folder') {
    return generatedCall(foldersApi.deleteFolder({ tenantName, path }))
  }
  return generatedCall(albumsApi.deleteAlbum({ tenantName, path }))
}

export function setAlbumCover(
  tenantName: string,
  albumPath: string,
  mediaId: string,
  hidden: boolean
): Promise<ExplorerAlbum> {
  return generatedCall(
    albumsApi.setCover({ tenantName, setAlbumCoverRequest: { albumPath, mediaId, hidden } })
  ) as Promise<ExplorerAlbum>
}

function createRequest(request: CreateLibraryItemRequest): GeneratedCreateLibraryItemRequest {
  return {
    name: request.name,
    displayName: request.displayName,
    parentPath: request.parentPath ?? undefined,
    scope: request.scope ?? undefined
  }
}

function updateRequest(request: UpdateLibraryItemRequest): GeneratedUpdateLibraryItemRequest {
  return {
    path: request.path,
    name: request.name ?? undefined,
    displayName: request.displayName ?? undefined
  }
}

function moveRequest(request: MoveLibraryItemRequest): GeneratedMoveLibraryItemRequest {
  return {
    path: request.path,
    newParentPath: request.newParentPath ?? undefined,
    name: request.name ?? undefined
  }
}
