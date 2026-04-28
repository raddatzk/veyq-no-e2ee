export type ExplorerContextType =
  | 'Tenant'
  | 'Folder'
  | 'Album'
  | 'tenant'
  | 'folder'
  | 'album'
  | string
export type LibraryItemType = 'folder' | 'album'

export type ExplorerContext = {
  type: ExplorerContextType
  path: string
  displayName: string
}

export type ExplorerBreadcrumb = {
  type: ExplorerContextType
  name: string
  displayName: string
  path: string
}

export type ExplorerFolder = {
  id: string
  name: string
  displayName: string
  path: string
}

export type ExplorerAlbum = {
  id: string
  name: string
  displayName: string
  path: string
  coverMediaId: string | null
}

export type ExplorerMedia = {
  id: string
  title: string
  albumPath: string | null
  ownerUserId: number
  createdAt: string
  mimeType: string
  sizeBytes: number
  contentUrl: string
}

export type ExplorerResponse = {
  context: ExplorerContext
  breadcrumbs: ExplorerBreadcrumb[]
  folders: ExplorerFolder[]
  albums: ExplorerAlbum[]
}

export type LibraryShares = {
  users: UserShare[]
  groups: GroupShare[]
}

export type UserShare = {
  userId: number
  email: string
  displayName: string
}

export type GroupShare = {
  groupId: number
  name: string
  displayName: string
}

export type CreateLibraryItemRequest = {
  parentPath: string | null
  name: string
  displayName: string
}

export type UpdateLibraryItemRequest = {
  path: string
  name: string
  displayName: string
}

export type MoveLibraryItemRequest = {
  path: string
  newParentPath: string | null
  name: string | null
}

export type StreamMediaOptions = {
  signal?: AbortSignal
}

export type UploadMediaRequest = {
  albumPath: string
  file: File
  title?: string
  onProgress?: (uploadedBytes: number, totalBytes: number) => void
}

export type MediaUploadSession = {
  uploadId: string
  chunkSizeBytes: number
}
