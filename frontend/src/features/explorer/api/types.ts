import type {
  ExplorerAlbum,
  ExplorerBreadcrumb,
  ExplorerFolder,
  ExplorerLocation,
  ExplorerResponse,
  ExplorerScopeRoot,
  LocationKind as ExplorerLocationKind,
  OwnerBadge
} from '../../../api/generated'
import type {
  CreateLibraryItemRequest as GeneratedCreateLibraryItemRequest,
  LibraryScopeRequest,
  MediaSummary,
  MediaUploadSessionSummary,
  MoveLibraryItemRequest as GeneratedMoveLibraryItemRequest,
  PendingMediaUploadSummary as GeneratedPendingMediaUploadSummary,
  UpdateLibraryItemRequest as GeneratedUpdateLibraryItemRequest,
  UpdateMediaMetadataRequest as GeneratedUpdateMediaMetadataRequest
} from '../../../api/generated'

export type {
  ExplorerAlbum,
  ExplorerBreadcrumb,
  ExplorerFolder,
  ExplorerLocation,
  ExplorerLocationKind,
  ExplorerResponse,
  ExplorerScopeRoot,
  LibraryScopeRequest,
  OwnerBadge,
}

export type LibraryItemType = 'folder' | 'album'
export type ExplorerMedia = Omit<
  MediaSummary,
  | 'albumPath'
  | 'capturedAt'
  | 'cameraMake'
  | 'cameraModel'
  | 'lensModel'
  | 'imageWidth'
  | 'imageHeight'
  | 'gpsLatitude'
  | 'gpsLongitude'
> & {
  albumPath: string | null
  capturedAt: string | null
  cameraMake: string | null
  cameraModel: string | null
  lensModel: string | null
  imageWidth: number | null
  imageHeight: number | null
  gpsLatitude: number | null
  gpsLongitude: number | null
}

export type CreateLibraryItemRequest = Omit<GeneratedCreateLibraryItemRequest, 'parentPath' | 'scope'> & {
  parentPath: string | null
  scope?: LibraryScopeRequest | null
}

export type UpdateLibraryItemRequest = Omit<
  GeneratedUpdateLibraryItemRequest,
  'path' | 'name' | 'displayName'
> & {
  path: string
  name: string | null
  displayName: string | null
}

export type MoveLibraryItemRequest = Omit<
  GeneratedMoveLibraryItemRequest,
  'newParentPath' | 'name'
> & {
  newParentPath: string | null
  name: string | null
}

export type UpdateMediaMetadataRequest = Omit<GeneratedUpdateMediaMetadataRequest, 'capturedAt'> & {
  capturedAt: string | null
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

export type MediaUploadSession = MediaUploadSessionSummary
export type PendingMediaUploadSession = Omit<GeneratedPendingMediaUploadSummary, 'title'> & {
  title: string | null
}
