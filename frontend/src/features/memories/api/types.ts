import type {
  CoverCrop,
  CreateMemoryMediaBucketRequest as GeneratedCreateMemoryMediaBucketRequest,
  CreateMemoryMediaUploadRequest,
  CreateMemoryRequest as GeneratedCreateMemoryRequest,
  CreateFolderRequest as GeneratedCreateFolderRequest,
  DeleteMemoryMediaBatchRequest as GeneratedDeleteMemoryMediaBatchRequest,
  MemoryFolder,
  MemoryHeaderSummary,
  MemoryJournalSummary,
  MemoryPlaylistProvider,
  MemoryPlaylistSummary,
  MemoryTrackActivityType,
  MemoryTrackSummary,
  MemoryMediaBucketSummary,
  MemoryMediaUploadSession,
  MediaUploader,
  MemoryNavigationResponse as GeneratedMemoryNavigationResponse,
  MemoryMediaSummary,
  MemoryParticipantSummary,
  MemoryScope,
  MemoryShareSummary,
  MemoryShareRole,
  PublicShareMemorySummary,
  PublicShareMemoryHeaderSummary,
  PublicShareMediaSummary,
  PublicShareNotice,
  PublicShareNoticeScope,
  PublicShareSummary,
  PublicShareTreeResponse,
  PendingMemoryMediaUploadSession,
  SearchResultSummary,
  MemoryScopeRequest,
  MemorySummary,
  MemoryScopeRoot,
  MoveFolderRequest as GeneratedMoveFolderRequest,
  MoveMemoryMediaRequest as GeneratedMoveMemoryMediaRequest,
  UpdateFolderRequest as GeneratedUpdateFolderRequest,
  UpdateMemoryMediaBucketRequest as GeneratedUpdateMemoryMediaBucketRequest,
  UpdateMemoryMediaMetadataBatchRequest as GeneratedUpdateMemoryMediaMetadataBatchRequest,
  UpdateMemoryJournalRequest as GeneratedUpdateMemoryJournalRequest,
  UpdateMemoryMediaMetadataRequest as GeneratedUpdateMemoryMediaMetadataRequest,
  UpdateMemoryPlaylistRequest as GeneratedUpdateMemoryPlaylistRequest,
  UpdateMemoryTrackRequest as GeneratedUpdateMemoryTrackRequest,
  UpdateMemoryRequest as GeneratedUpdateMemoryRequest
} from '../../../api/generated'

export type {
  CoverCrop,
  MemoryScope,
  MemoryScopeRequest,
  MemoryFolder,
  MemoryScopeRoot,
  MediaUploader,
  MemoryParticipantSummary,
  MemoryShareSummary,
  MemoryShareRole,
  MemoryPlaylistProvider,
  MemoryTrackActivityType,
  PublicShareMemorySummary,
  PublicShareMediaSummary,
  PublicShareNotice,
  PublicShareNoticeScope,
  PublicShareSummary,
  PublicShareTreeResponse,
  SearchResultSummary,
  MemoryJournalSummary,
  MemoryMediaBucketSummary,
  MemoryMediaUploadSession,
  PendingMemoryMediaUploadSession,
  CreateMemoryMediaUploadRequest,
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

export type MemoryNavigationResponse = Omit<GeneratedMemoryNavigationResponse, 'favoriteMemories'> & {
  favoriteMemories: Memory[]
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

export type MemoryTrack = MemoryTrackSummary

export type UpdateMemoryTrackRequest = GeneratedUpdateMemoryTrackRequest

export type MemoryPlaylist = MemoryPlaylistSummary

export type UpdateMemoryPlaylistRequest = GeneratedUpdateMemoryPlaylistRequest

export type CreateMemoryPlaylistRequest = {
  title?: string | null
  note?: string | null
  featured?: boolean
  url: string
}

export type UploadMemoryTrackRequest = {
  file: File
  title: string
  activityType: MemoryTrackActivityType
}

export type MemoryMedia = Omit<
  MemoryMediaSummary,
  | 'capturedAt'
  | 'thumbnailUrl'
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
  thumbnailUrl: string | null
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
export type MemoryMediaBucket = MemoryMediaBucketSummary
export type CreateMemoryMediaBucketRequest = GeneratedCreateMemoryMediaBucketRequest
export type UpdateMemoryMediaBucketRequest = GeneratedUpdateMemoryMediaBucketRequest
export type MoveMemoryMediaRequest = GeneratedMoveMemoryMediaRequest
export type DeleteMemoryMediaBatchRequest = GeneratedDeleteMemoryMediaBatchRequest

export type UploadMemoryMediaRequest = {
  file: File
  title?: string | null
  bucketId?: string | null
}

export type UploadMediaProgress = {
  loaded: number
  total: number | null
}

export type UploadMediaOptions = {
  onProgress?: (progress: UploadMediaProgress) => void
  onSession?: (session: MemoryMediaUploadSession | PendingMemoryMediaUploadSession) => void
  signal?: AbortSignal
}

export type CreateFolderRequest = Omit<GeneratedCreateFolderRequest, 'parentPath' | 'scope'> & {
  parentPath?: string | null
  scope?: MemoryScopeRequest | null
}

export type UpdateFolderRequest = GeneratedUpdateFolderRequest

export type MoveFolderRequest = GeneratedMoveFolderRequest
