export type ExplorerLocationKind = 'TENANT' | 'GROUP' | 'USER' | 'FOLDER' | 'ALBUM'
export type LibraryItemType = 'folder' | 'album'

export type ExplorerLocation = {
  kind: ExplorerLocationKind
  path: string
  displayName: string
}

export type ExplorerBreadcrumb = {
  kind: ExplorerLocationKind
  displayName: string
  label: string
  path: string
}

export type OwnerBadge = {
  kind: 'TENANT' | 'GROUP' | 'USER'
  displayName: string
  name?: string
}

export type ExplorerFolder = {
  id: string
  name: string
  displayName: string
  path: string
  ownerBadge?: OwnerBadge | null
}

export type ExplorerAlbum = {
  id: string
  name: string
  displayName: string
  path: string
  coverMediaId: string | null
  ownerBadge?: OwnerBadge | null
}

export type ExplorerScopeRoot = {
  kind: 'GROUP' | 'USER'
  name: string
  displayName: string
  path: string
}

export type ExplorerMedia = {
  id: string
  title: string
  albumPath: string | null
  ownerUserId: number
  uploaderDisplayName: string
  createdAt: string
  capturedAt: string | null
  capturedAtOverride: boolean
  mimeType: string
  sizeBytes: number
  contentUrl: string
  cameraMake: string | null
  cameraModel: string | null
  lensModel: string | null
  imageWidth: number | null
  imageHeight: number | null
  gpsLatitude: number | null
  gpsLongitude: number | null
}

export type ExplorerResponse = {
  location: ExplorerLocation
  breadcrumbs: ExplorerBreadcrumb[]
  scopeRoots: ExplorerScopeRoot[]
  folders: ExplorerFolder[]
  albums: ExplorerAlbum[]
}

export type CreateLibraryItemRequest = {
  parentPath: string | null
  name: string
  displayName: string
  scope?: {
    type: 'TENANT' | 'GROUP' | 'USER'
    groupName?: string | null
  } | null
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
  batchSize?: number
}

export type UploadMediaRequest = {
  albumPath: string
  file: File
  title?: string
  existingUploadId?: string
  signal?: AbortSignal
  onSession?: (session: MediaUploadSession | PendingMediaUploadSession) => void
  onProgress?: (uploadedBytes: number, totalBytes: number) => void
}

export type UpdateMediaMetadataRequest = {
  capturedAt: string | null
}

export type MediaUploadSession = {
  uploadId: string
  chunkSizeBytes: number
}

export type PendingMediaUploadSession = {
  uploadId: string
  tenantName: string
  tenantDisplayName: string
  albumPath: string
  filename: string
  mimeType: string
  sizeBytes: number
  title: string | null
  chunkSizeBytes: number
  uploadedChunks: number[]
  uploadedBytes: number
  createdAt: string
  updatedAt: string
}
