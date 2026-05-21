import type {
  CreateMemoryRequest as GeneratedCreateMemoryRequest,
  CreateFolderRequest as GeneratedCreateFolderRequest,
  MemoryFolder,
  MemoryNavigationResponse,
  MemoryMediaSummary,
  MemoryScope,
  MemoryScopeRequest,
  MemorySummary,
  MemoryScopeRoot,
  MoveFolderRequest as GeneratedMoveFolderRequest,
  SetMemoryCoverRequest as GeneratedSetMemoryCoverRequest,
  UpdateFolderRequest as GeneratedUpdateFolderRequest,
  UpdateMemoryMediaMetadataRequest as GeneratedUpdateMemoryMediaMetadataRequest,
  UpdateMemoryRequest as GeneratedUpdateMemoryRequest
} from '../../../api/generated'

export type {
  MemoryScope,
  MemoryScopeRequest,
  MemoryFolder,
  MemoryNavigationResponse,
  MemoryScopeRoot,
}

export type Memory = Omit<MemorySummary, 'parentId' | 'coverMediaId' | 'scope'> & {
  parentId: string | null
  coverMediaId: string | null
  scope: MemoryScope | null
}

export type MemoryMedia = Omit<
  MemoryMediaSummary,
  | 'capturedAt'
  | 'previewUrl'
  | 'cameraMake'
  | 'cameraModel'
  | 'lensModel'
  | 'imageWidth'
  | 'imageHeight'
  | 'gpsLatitude'
  | 'gpsLongitude'
> & {
  capturedAt: string | null
  previewUrl: string | null
  cameraMake: string | null
  cameraModel: string | null
  lensModel: string | null
  imageWidth: number | null
  imageHeight: number | null
  gpsLatitude: number | null
  gpsLongitude: number | null
}

export type CreateMemoryRequest = Omit<GeneratedCreateMemoryRequest, 'parentId' | 'folderPath' | 'scope'> & {
  parentId?: string | null
  folderPath?: string | null
  scope?: MemoryScopeRequest | null
}

export type UpdateMemoryRequest = Omit<
  GeneratedUpdateMemoryRequest,
  'parentId' | 'name' | 'displayName'
> & {
  parentId?: string | null
  name?: string | null
  displayName?: string | null
}

export type SetMemoryCoverRequest = GeneratedSetMemoryCoverRequest

export type UpdateMemoryMediaMetadataRequest = Omit<
  GeneratedUpdateMemoryMediaMetadataRequest,
  'capturedAt'
> & {
  capturedAt?: string | null
}

export type UploadMemoryMediaRequest = {
  file: File
  title?: string | null
}

export type CreateFolderRequest = Omit<GeneratedCreateFolderRequest, 'parentPath' | 'scope'> & {
  parentPath?: string | null
  scope?: MemoryScopeRequest | null
}

export type UpdateFolderRequest = GeneratedUpdateFolderRequest

export type MoveFolderRequest = GeneratedMoveFolderRequest
