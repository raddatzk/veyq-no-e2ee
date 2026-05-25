import type {
  CoverCrop,
  CreateMemoryRequest as GeneratedCreateMemoryRequest,
  CreateFolderRequest as GeneratedCreateFolderRequest,
  MemoryFolder,
  MemoryHeaderSummary,
  MemoryJournalSummary,
  MediaUploader,
  MemoryNavigationResponse,
  MemoryMediaSummary,
  MemoryScope,
  MemoryShareSummary,
  MemoryShareRole,
  PublicShareMemorySummary,
  PublicShareMemoryHeaderSummary,
  PublicShareMediaSummary,
  PublicShareSummary,
  PublicShareTreeResponse,
  MemoryScopeRequest,
  MemorySummary,
  MemoryScopeRoot,
  MoveFolderRequest as GeneratedMoveFolderRequest,
  UpdateFolderRequest as GeneratedUpdateFolderRequest,
  UpdateMemoryMediaMetadataBatchRequest as GeneratedUpdateMemoryMediaMetadataBatchRequest,
  UpdateMemoryJournalRequest as GeneratedUpdateMemoryJournalRequest,
  UpdateMemoryMediaMetadataRequest as GeneratedUpdateMemoryMediaMetadataRequest,
  UpdateMemoryRequest as GeneratedUpdateMemoryRequest
} from '../../../api/generated'

export type {
  CoverCrop,
  MemoryScope,
  MemoryScopeRequest,
  MemoryFolder,
  MemoryNavigationResponse,
  MemoryScopeRoot,
  MediaUploader,
  MemoryShareSummary,
  MemoryShareRole,
  PublicShareMemorySummary,
  PublicShareMediaSummary,
  PublicShareSummary,
  PublicShareTreeResponse,
  MemoryJournalSummary,
}

export type Memory = Omit<
  MemorySummary,
  'parentId' | 'coverMediaId' | 'coverUrl' | 'coverCrop' | 'scope'
> & {
  parentId: string | null
  coverMediaId: string | null
  coverUrl: string | null
  coverCrop: CoverCrop | null
  scope: MemoryScope | null
}

export type MemoryHeader = Omit<
  MemoryHeaderSummary,
  'coverUrl' | 'coverCrop' | 'scope'
> & {
  coverUrl: string | null
  coverCrop: CoverCrop | null
  scope: MemoryScope | null
}

export type PublicShareMemoryHeader = Omit<
  PublicShareMemoryHeaderSummary,
  'coverUrl' | 'coverCrop'
> & {
  coverUrl: string | null
  coverCrop: CoverCrop | null
  scope: null
}

export type MemoryJournal = MemoryJournalSummary

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
  'parentId' | 'name' | 'displayName' | 'startDate' | 'endDate'
> & {
  parentId?: string | null
  name?: string | null
  displayName?: string | null
  startDate?: string | null
  endDate?: string | null
}

export type UpdateMemoryJournalRequest = GeneratedUpdateMemoryJournalRequest

export type SetMemoryCoverRequest = {
  file?: File | Blob | null
  mediaId?: string | null
  cropX: number
  cropY: number
  cropWidth: number
  cropHeight: number
}

export type UpdateMemoryMediaMetadataRequest = Omit<
  GeneratedUpdateMemoryMediaMetadataRequest,
  'capturedAt'
> & {
  capturedAt?: string | null
}

export type UpdateMemoryMediaMetadataBatchRequest = GeneratedUpdateMemoryMediaMetadataBatchRequest

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
