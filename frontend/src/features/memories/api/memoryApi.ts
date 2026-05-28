import {
  foldersApi,
  generatedCall,
  memoriesApi,
  memoryMediaApi,
  memoryNavigationApi,
  memoryParticipantsApi,
  memoryPlaylistsApi,
  memorySharesApi,
  memoryTracksApi,
  publicSharesApi,
  searchApi
} from '../../../api/generatedClient'
import type {
  CreateFolderRequest as GeneratedCreateFolderRequest,
  CreateMemoryMediaBucketRequest,
  CreateMemoryMediaUploadRequest,
  CreateMemoryPlaylistRequest as GeneratedCreateMemoryPlaylistRequest,
  CreateMemoryRequest as GeneratedCreateMemoryRequest,
  CreateMemoryShareRequest as GeneratedCreateMemoryShareRequest,
  CreatePublicShareMemoryRequest as GeneratedCreatePublicShareMemoryRequest,
  DeleteMemoryMediaBatchRequest,
  MemoryMediaSummary,
  MemoryParticipantSummary,
  MemoryMediaUploadSession,
  MemoryPlaylistSummary,
  MemoryShareRole,
  MemoryTrackSummary,
  PendingMemoryMediaUploadSession,
  PublicShareMemoryHeaderSummary,
  PublicShareMediaSummary,
  PublicShareMemorySummary,
  MoveMemoryMediaRequest,
  UpdateMemoryMediaBucketRequest,
  UpdateMemoryMediaMetadataBatchRequest as GeneratedUpdateMemoryMediaMetadataBatchRequest,
  UpdateMemoryMediaMetadataRequest as GeneratedUpdateMemoryMediaMetadataRequest,
  UpdateMemoryPlaylistRequest as GeneratedUpdateMemoryPlaylistRequest,
  UpdateMemoryTrackRequest as GeneratedUpdateMemoryTrackRequest,
  UpdateMemoryRequest as GeneratedUpdateMemoryRequest,
  UpdatePublicShareMemoryRequest as GeneratedUpdatePublicShareMemoryRequest
} from '../../../api/generated'
import { callApi, uploadBlob } from '../../../shared/api/httpClient'
import type {
  CreateFolderRequest,
  CreateMemoryPlaylistRequest,
  CreateMemoryRequest,
  Memory,
  MemoryFolder,
  MemoryHeader,
  MemoryJournal,
  MemoryMedia,
  MemoryMediaBucket,
  MemoryNavigationResponse,
  MemoryPlaylist,
  MemoryTrack,
  SearchResultSummary,
  SetMemoryCoverRequest,
  CreateMemoryMediaBucketRequest as FeatureCreateMemoryMediaBucketRequest,
  DeleteMemoryMediaBatchRequest as FeatureDeleteMemoryMediaBatchRequest,
  MoveMemoryMediaRequest as FeatureMoveMemoryMediaRequest,
  UpdateMemoryMediaMetadataBatchRequest,
  UpdateMemoryJournalRequest,
  UpdateMemoryMediaMetadataRequest,
  UpdateMemoryMediaBucketRequest as FeatureUpdateMemoryMediaBucketRequest,
  UpdateMemoryPlaylistRequest,
  UpdateMemoryTrackRequest,
  UpdateMemoryRequest,
  UploadMediaOptions,
  UploadMemoryMediaRequest,
  UploadMemoryTrackRequest
} from './types'

export function getMemoryNavigation(
  tenantName: string,
  path: string
): Promise<MemoryNavigationResponse> {
  return generatedCall(memoryNavigationApi.getMemoryNavigation({ tenantName, path })).then(
    (navigation) => ({
      ...navigation,
      favoriteMemories: navigation.favoriteMemories.map(normalizeMemory)
    })
  )
}

export function searchTenant(
  tenantName: string,
  query: string,
  limit = 20
): Promise<SearchResultSummary[]> {
  return generatedCall(searchApi.searchTenant({ tenantName, q: query, limit }))
}

export function createFolder(
  tenantName: string,
  request: CreateFolderRequest
): Promise<MemoryFolder> {
  return generatedCall(
    foldersApi.createFolder({
      tenantName,
      createFolderRequest: createFolderRequest(request)
    })
  )
}

export function listMemories(tenantName: string): Promise<Memory[]> {
  return generatedCall(memoriesApi.listMemories({ tenantName })).then((memories) =>
    memories.map(normalizeMemory)
  )
}

export function listMemoriesByFolder(tenantName: string, folderPath: string): Promise<Memory[]> {
  return generatedCall(memoriesApi.listMemoriesByFolder({ tenantName, folderPath })).then(
    (memories) => memories.map(normalizeMemory)
  )
}

export function getMemory(tenantName: string, memoryId: string): Promise<Memory> {
  return generatedCall(memoriesApi.getMemory({ tenantName, memoryId })).then(normalizeMemory)
}

export function favoriteMemory(tenantName: string, memoryId: string): Promise<Memory> {
  return generatedCall(memoriesApi.favoriteMemory({ tenantName, memoryId })).then(normalizeMemory)
}

export function unfavoriteMemory(tenantName: string, memoryId: string): Promise<Memory> {
  return generatedCall(memoriesApi.unfavoriteMemory({ tenantName, memoryId })).then(normalizeMemory)
}

export function getMemoryHeader(tenantName: string, memoryId: string): Promise<MemoryHeader> {
  return generatedCall(memoriesApi.getMemoryHeader({ tenantName, memoryId })).then(
    normalizeMemoryHeader
  )
}

export function getMemoryJournal(tenantName: string, memoryId: string): Promise<MemoryJournal> {
  return generatedCall(memoriesApi.getMemoryJournal({ tenantName, memoryId }))
}

export function getMemoryByPath(tenantName: string, path: string): Promise<Memory> {
  return generatedCall(memoriesApi.getMemoryByPath({ tenantName, path })).then(normalizeMemory)
}

export function createMemory(tenantName: string, request: CreateMemoryRequest): Promise<Memory> {
  return generatedCall(
    memoriesApi.createMemory({
      tenantName,
      createMemoryRequest: createMemoryRequest(request)
    })
  ).then(normalizeMemory)
}

export function updateMemory(
  tenantName: string,
  memoryId: string,
  request: UpdateMemoryRequest
): Promise<Memory> {
  return generatedCall(
    memoriesApi.updateMemory({
      tenantName,
      memoryId,
      updateMemoryRequest: updateMemoryRequest(request)
    })
  ).then(normalizeMemory)
}

export function deleteMemory(tenantName: string, memoryId: string): Promise<void> {
  return generatedCall(memoriesApi.deleteMemory({ tenantName, memoryId }))
}

export function updateMemoryJournal(
  tenantName: string,
  memoryId: string,
  request: UpdateMemoryJournalRequest
): Promise<MemoryJournal> {
  return generatedCall(
    memoriesApi.updateMemoryJournal({
      tenantName,
      memoryId,
      updateMemoryJournalRequest: request
    })
  )
}

export function listChildMemories(tenantName: string, memoryId: string): Promise<Memory[]> {
  return generatedCall(memoriesApi.listChildMemories({ tenantName, memoryId })).then((memories) =>
    memories.map(normalizeMemory)
  )
}

export function listMemoryParticipants(
  tenantName: string,
  memoryId: string
): Promise<MemoryParticipantSummary[]> {
  return generatedCall(memoryParticipantsApi.listMemoryParticipants({ tenantName, memoryId }))
}

export function listMemoryParticipantCandidates(
  tenantName: string,
  memoryId: string
): Promise<MemoryParticipantSummary[]> {
  return generatedCall(memoryParticipantsApi.listMemoryParticipantCandidates({ tenantName, memoryId }))
}

export function addMemoryParticipant(
  tenantName: string,
  memoryId: string,
  userId: number
): Promise<MemoryParticipantSummary> {
  return generatedCall(memoryParticipantsApi.addMemoryParticipant({ tenantName, memoryId, userId }))
}

export function removeMemoryParticipant(
  tenantName: string,
  memoryId: string,
  userId: number
): Promise<void> {
  return generatedCall(memoryParticipantsApi.removeMemoryParticipant({ tenantName, memoryId, userId }))
}

export function setMemoryCover(
  tenantName: string,
  memoryId: string,
  request: SetMemoryCoverRequest
): Promise<Memory> {
  return generatedCall(
    memoriesApi.setMemoryCover({
      tenantName,
      memoryId,
      file: request.file || undefined,
      mediaId: request.mediaId || undefined,
      cropX: request.cropX,
      cropY: request.cropY,
      cropWidth: request.cropWidth,
      cropHeight: request.cropHeight
    })
  ).then(normalizeMemory)
}

export function removeMemoryCover(tenantName: string, memoryId: string): Promise<Memory> {
  return generatedCall(memoriesApi.removeMemoryCover({ tenantName, memoryId })).then(
    normalizeMemory
  )
}

export function listMemoryMedia(
  tenantName: string,
  memoryId: string,
  includeChildren = false
): Promise<MemoryMedia[]> {
  return generatedCall(
    memoryMediaApi.listMemoryMedia({ tenantName, memoryId, includeChildren })
  ).then((media) => media.map(normalizeMemoryMedia))
}

export function listMemoryMediaBuckets(
  tenantName: string,
  memoryId: string,
  includeChildren = false
): Promise<MemoryMediaBucket[]> {
  return generatedCall(memoryMediaApi.listMemoryMediaBuckets({ tenantName, memoryId, includeChildren }))
}

export function createMemoryMediaBucket(
  tenantName: string,
  memoryId: string,
  request: FeatureCreateMemoryMediaBucketRequest
): Promise<MemoryMediaBucket> {
  return generatedCall(
    memoryMediaApi.createMemoryMediaBucket({
      tenantName,
      memoryId,
      createMemoryMediaBucketRequest: request as CreateMemoryMediaBucketRequest
    })
  )
}

export function updateMemoryMediaBucket(
  tenantName: string,
  memoryId: string,
  bucketId: string,
  request: FeatureUpdateMemoryMediaBucketRequest
): Promise<MemoryMediaBucket> {
  return generatedCall(
    memoryMediaApi.updateMemoryMediaBucket({
      tenantName,
      memoryId,
      bucketId,
      updateMemoryMediaBucketRequest: request as UpdateMemoryMediaBucketRequest
    })
  )
}

export function deleteMemoryMediaBucket(
  tenantName: string,
  memoryId: string,
  bucketId: string
): Promise<void> {
  return generatedCall(memoryMediaApi.deleteMemoryMediaBucket({ tenantName, memoryId, bucketId }))
}

export function uploadMemoryMedia(
  tenantName: string,
  memoryId: string,
  request: UploadMemoryMediaRequest,
  options: UploadMediaOptions = {}
): Promise<MemoryMedia> {
  const basePath = `/api/${encodeURIComponent(tenantName)}/memories/${encodeURIComponent(memoryId)}/media/uploads`
  return uploadChunkedMedia<MemoryMediaSummary>(
    basePath,
    request,
    options,
  ).then(normalizeMemoryMedia)
}

export function moveMemoryMedia(
  tenantName: string,
  memoryId: string,
  request: FeatureMoveMemoryMediaRequest
): Promise<MemoryMedia[]> {
  return generatedCall(
    memoryMediaApi.moveMemoryMedia({
      tenantName,
      memoryId,
      moveMemoryMediaRequest: request as MoveMemoryMediaRequest
    })
  ).then((response) => response.media.map(normalizeMemoryMedia))
}

export function deleteMemoryMediaBatch(
  tenantName: string,
  memoryId: string,
  request: FeatureDeleteMemoryMediaBatchRequest
): Promise<void> {
  return generatedCall(
    memoryMediaApi.deleteMemoryMediaBatch({
      tenantName,
      memoryId,
      deleteMemoryMediaBatchRequest: request as DeleteMemoryMediaBatchRequest
    })
  )
}

export function listMemoryTracks(tenantName: string, memoryId: string): Promise<MemoryTrack[]> {
  return generatedCall(memoryTracksApi.listMemoryTracks({ tenantName, memoryId })).then((tracks) =>
    tracks.map(normalizeMemoryTrack)
  )
}

export function uploadMemoryTrack(
  tenantName: string,
  memoryId: string,
  request: UploadMemoryTrackRequest
): Promise<MemoryTrack> {
  return generatedCall(
    memoryTracksApi.uploadMemoryTrack({
      tenantName,
      memoryId,
      file: request.file,
      title: request.title,
      activityType: request.activityType
    })
  ).then(normalizeMemoryTrack)
}

export function updateMemoryTrack(
  tenantName: string,
  memoryId: string,
  trackId: string,
  request: UpdateMemoryTrackRequest
): Promise<MemoryTrack> {
  return generatedCall(
    memoryTracksApi.updateMemoryTrack({
      tenantName,
      memoryId,
      trackId,
      updateMemoryTrackRequest: request as GeneratedUpdateMemoryTrackRequest
    })
  ).then(normalizeMemoryTrack)
}

export function deleteMemoryTrack(tenantName: string, memoryId: string, trackId: string): Promise<void> {
  return generatedCall(memoryTracksApi.deleteMemoryTrack({ tenantName, memoryId, trackId }))
}

export function listMemoryPlaylists(tenantName: string, memoryId: string): Promise<MemoryPlaylist[]> {
  return generatedCall(memoryPlaylistsApi.listMemoryPlaylists({ tenantName, memoryId })).then(
    (playlists) => playlists.map(normalizeMemoryPlaylist)
  )
}

export function createMemoryPlaylist(
  tenantName: string,
  memoryId: string,
  request: CreateMemoryPlaylistRequest
): Promise<MemoryPlaylist> {
  return generatedCall(
    memoryPlaylistsApi.createMemoryPlaylist({
      tenantName,
      memoryId,
      createMemoryPlaylistRequest: createMemoryPlaylistRequest(request)
    })
  ).then(normalizeMemoryPlaylist)
}

export function updateMemoryPlaylist(
  tenantName: string,
  memoryId: string,
  playlistId: string,
  request: UpdateMemoryPlaylistRequest
): Promise<MemoryPlaylist> {
  return generatedCall(
    memoryPlaylistsApi.updateMemoryPlaylist({
      tenantName,
      memoryId,
      playlistId,
      updateMemoryPlaylistRequest: request as GeneratedUpdateMemoryPlaylistRequest
    })
  ).then(normalizeMemoryPlaylist)
}

export function deleteMemoryPlaylist(tenantName: string, memoryId: string, playlistId: string): Promise<void> {
  return generatedCall(memoryPlaylistsApi.deleteMemoryPlaylist({ tenantName, memoryId, playlistId }))
}

export function updateMemoryMediaMetadata(
  tenantName: string,
  memoryId: string,
  mediaId: string,
  request: UpdateMemoryMediaMetadataRequest
): Promise<MemoryMedia> {
  return generatedCall(
    memoryMediaApi.updateMemoryMediaMetadata({
      tenantName,
      memoryId,
      mediaId,
      updateMemoryMediaMetadataRequest: request as GeneratedUpdateMemoryMediaMetadataRequest
    })
  ).then(normalizeMemoryMedia)
}

export function updateMemoryMediaMetadataBatch(
  tenantName: string,
  memoryId: string,
  request: UpdateMemoryMediaMetadataBatchRequest
): Promise<MemoryMedia[]> {
  return generatedCall(
    memoryMediaApi.updateMemoryMediaMetadataBatch({
      tenantName,
      memoryId,
      updateMemoryMediaMetadataBatchRequest:
        request as GeneratedUpdateMemoryMediaMetadataBatchRequest
    })
  ).then((response) => response.media.map(normalizeMemoryMedia))
}

export function deleteMemoryMedia(
  tenantName: string,
  memoryId: string,
  mediaId: string
): Promise<void> {
  return generatedCall(memoryMediaApi.deleteMemoryMedia({ tenantName, memoryId, mediaId }))
}

export function deleteAllMemoryMedia(tenantName: string, memoryId: string): Promise<void> {
  return generatedCall(memoryMediaApi.deleteAllMemoryMedia({ tenantName, memoryId }))
}

export function listMemoryShares(tenantName: string, memoryId: string) {
  return generatedCall(memorySharesApi.listMemoryShares({ tenantName, memoryId }))
}

export function createMemoryShare(
  tenantName: string,
  memoryId: string,
  request: {
    name?: string | null
    role: MemoryShareRole
    password?: string | null
    noticeMarkdownByLocale?: Record<string, string>
  }
) {
  const createMemoryShareRequest = {
    name: request.name,
    role: request.role,
    password: request.password,
    noticeMarkdownByLocale: request.noticeMarkdownByLocale
  } as unknown as GeneratedCreateMemoryShareRequest

  return generatedCall(
    memorySharesApi.createMemoryShare({
      tenantName,
      memoryId,
      createMemoryShareRequest
    })
  )
}

export function updateMemoryShare(
  tenantName: string,
  shareId: string,
  request: {
    name?: string | null
    role?: MemoryShareRole
    enabled?: boolean
    password?: string | null
    noticeMarkdownByLocale?: Record<string, string>
  }
) {
  return generatedCall(
    memorySharesApi.updateMemoryShare({
      tenantName,
      shareId,
      updateMemoryShareRequest: {
        name: request.name === null ? '' : request.name,
        role: request.role,
        enabled: request.enabled,
        password: request.password === null ? '' : request.password,
        noticeMarkdownByLocale: request.noticeMarkdownByLocale
      }
    })
  )
}

export function getPublicShare(shareId: string) {
  return generatedCall(publicSharesApi.getPublicShare({ shareId }))
}

export function loginPublicShare(shareId: string, password: string | null, displayName?: string | null) {
  const normalizedDisplayName = displayName?.trim()
  return generatedCall(
    publicSharesApi.loginPublicShare({
      shareId,
      publicShareLoginRequest: {
        password: password ?? undefined,
        displayName: normalizedDisplayName || undefined
      }
    })
  )
}

export function refreshPublicShare(shareId: string) {
  return generatedCall(publicSharesApi.refreshPublicShare({ shareId }))
}

export function logoutPublicShare(shareId: string) {
  return generatedCall(publicSharesApi.logoutPublicShare({ shareId }))
}

export function getPublicShareTree(shareId: string) {
  return generatedCall(publicSharesApi.getPublicShareTree({ shareId }))
}

export function listPublicShareMedia(shareId: string) {
  return generatedCall(publicSharesApi.listPublicShareMedia({ shareId })).then((media) =>
    media.map(normalizePublicShareMedia)
  )
}

export function uploadPublicShareMedia(
  shareId: string,
  request: { file: File; title?: string | null },
  options: UploadMediaOptions = {}
) {
  const basePath = `/api/public-shares/${encodeURIComponent(shareId)}/media/uploads`
  return uploadChunkedMedia<PublicShareMediaSummary>(
    basePath,
    request,
    options,
  ).then(normalizePublicShareMedia)
}

export function getPublicShareMemoryByPath(shareId: string, path: string): Promise<Memory> {
  return generatedCall(publicSharesApi.getPublicShareMemoryByPath({ shareId, path })).then(
    normalizePublicShareMemory
  )
}

export function getPublicShareMemoryHeader(
  shareId: string,
  memoryId: string
): Promise<MemoryHeader> {
  return generatedCall(publicSharesApi.getPublicShareMemoryHeader({ shareId, memoryId })).then(
    normalizePublicShareMemoryHeader
  )
}

export function getPublicShareMemoryJournal(
  shareId: string,
  memoryId: string
): Promise<MemoryJournal> {
  return generatedCall(publicSharesApi.getPublicShareMemoryJournal({ shareId, memoryId }))
}

export function listPublicShareChildMemories(
  shareId: string,
  memoryId: string
): Promise<Memory[]> {
  return generatedCall(publicSharesApi.listPublicShareChildMemories({ shareId, memoryId })).then(
    (memories) => memories.map(normalizePublicShareMemory)
  )
}

export function createPublicShareChildMemory(
  shareId: string,
  memoryId: string,
  request: CreateMemoryRequest
): Promise<Memory> {
  return generatedCall(
    publicSharesApi.createPublicShareChildMemory({
      shareId,
      memoryId,
      createPublicShareMemoryRequest:
        createPublicShareMemoryRequest(request) as GeneratedCreatePublicShareMemoryRequest
    })
  ).then(normalizePublicShareMemory)
}

export function updatePublicShareMemory(
  shareId: string,
  memoryId: string,
  request: UpdateMemoryRequest
): Promise<Memory> {
  return generatedCall(
    publicSharesApi.updatePublicShareMemory({
      shareId,
      memoryId,
      updatePublicShareMemoryRequest:
        updatePublicShareMemoryRequest(request) as GeneratedUpdatePublicShareMemoryRequest
    })
  ).then(normalizePublicShareMemory)
}

export function deletePublicShareMemory(shareId: string, memoryId: string): Promise<void> {
  return generatedCall(publicSharesApi.deletePublicShareMemory({ shareId, memoryId }))
}

export function updatePublicShareMemoryJournal(
  shareId: string,
  memoryId: string,
  request: UpdateMemoryJournalRequest
): Promise<MemoryJournal> {
  return generatedCall(
    publicSharesApi.updatePublicShareMemoryJournal({
      shareId,
      memoryId,
      updateMemoryJournalRequest: request
    })
  )
}

export function listPublicShareMemoryMedia(
  shareId: string,
  memoryId: string,
  includeChildren = false
): Promise<MemoryMedia[]> {
  return generatedCall(publicSharesApi.listPublicShareMemoryMedia({ shareId, memoryId, includeChildren })).then(
    (media) => media.map(normalizePublicShareMedia)
  )
}

export function listPublicShareMemoryMediaBuckets(
  shareId: string,
  memoryId: string,
  includeChildren = false
): Promise<MemoryMediaBucket[]> {
  return generatedCall(publicSharesApi.listPublicShareMemoryMediaBuckets({ shareId, memoryId, includeChildren }))
}

export function createPublicShareMemoryMediaBucket(
  shareId: string,
  memoryId: string,
  request: FeatureCreateMemoryMediaBucketRequest
): Promise<MemoryMediaBucket> {
  return generatedCall(
    publicSharesApi.createPublicShareMemoryMediaBucket({
      shareId,
      memoryId,
      createMemoryMediaBucketRequest: request as CreateMemoryMediaBucketRequest
    })
  )
}

export function updatePublicShareMemoryMediaBucket(
  shareId: string,
  memoryId: string,
  bucketId: string,
  request: FeatureUpdateMemoryMediaBucketRequest
): Promise<MemoryMediaBucket> {
  return generatedCall(
    publicSharesApi.updatePublicShareMemoryMediaBucket({
      shareId,
      memoryId,
      bucketId,
      updateMemoryMediaBucketRequest: request as UpdateMemoryMediaBucketRequest
    })
  )
}

export function deletePublicShareMemoryMediaBucket(
  shareId: string,
  memoryId: string,
  bucketId: string
): Promise<void> {
  return generatedCall(publicSharesApi.deletePublicShareMemoryMediaBucket({ shareId, memoryId, bucketId }))
}

export function listPublicShareMemoryTracks(
  shareId: string,
  memoryId: string
): Promise<MemoryTrack[]> {
  return generatedCall(publicSharesApi.listPublicShareMemoryTracks({ shareId, memoryId })).then(
    (tracks) => tracks.map(normalizeMemoryTrack)
  )
}

export function listPublicShareMemoryPlaylists(
  shareId: string,
  memoryId: string
): Promise<MemoryPlaylist[]> {
  return generatedCall(publicSharesApi.listPublicShareMemoryPlaylists({ shareId, memoryId })).then(
    (playlists) => playlists.map(normalizeMemoryPlaylist)
  )
}

export function createPublicShareMemoryPlaylist(
  shareId: string,
  memoryId: string,
  request: CreateMemoryPlaylistRequest
): Promise<MemoryPlaylist> {
  return generatedCall(
    publicSharesApi.createPublicShareMemoryPlaylist({
      shareId,
      memoryId,
      createMemoryPlaylistRequest: createMemoryPlaylistRequest(request)
    })
  ).then(normalizeMemoryPlaylist)
}

export function updatePublicShareMemoryPlaylist(
  shareId: string,
  memoryId: string,
  playlistId: string,
  request: UpdateMemoryPlaylistRequest
): Promise<MemoryPlaylist> {
  return generatedCall(
    publicSharesApi.updatePublicShareMemoryPlaylist({
      shareId,
      memoryId,
      playlistId,
      updateMemoryPlaylistRequest: request as GeneratedUpdateMemoryPlaylistRequest
    })
  ).then(normalizeMemoryPlaylist)
}

export function deletePublicShareMemoryPlaylist(
  shareId: string,
  memoryId: string,
  playlistId: string
): Promise<void> {
  return generatedCall(publicSharesApi.deletePublicShareMemoryPlaylist({ shareId, memoryId, playlistId }))
}

export function uploadPublicShareMemoryTrack(
  shareId: string,
  memoryId: string,
  request: UploadMemoryTrackRequest
): Promise<MemoryTrack> {
  return generatedCall(
    publicSharesApi.uploadPublicShareMemoryTrack({
      shareId,
      memoryId,
      file: request.file,
      title: request.title,
      activityType: request.activityType
    })
  ).then(normalizeMemoryTrack)
}

export function updatePublicShareMemoryTrack(
  shareId: string,
  memoryId: string,
  trackId: string,
  request: UpdateMemoryTrackRequest
): Promise<MemoryTrack> {
  return generatedCall(
    publicSharesApi.updatePublicShareMemoryTrack({
      shareId,
      memoryId,
      trackId,
      updateMemoryTrackRequest: request as GeneratedUpdateMemoryTrackRequest
    })
  ).then(normalizeMemoryTrack)
}

export function deletePublicShareMemoryTrack(
  shareId: string,
  memoryId: string,
  trackId: string
): Promise<void> {
  return generatedCall(publicSharesApi.deletePublicShareMemoryTrack({ shareId, memoryId, trackId }))
}

export function uploadPublicShareMemoryMedia(
  shareId: string,
  memoryId: string,
  request: UploadMemoryMediaRequest,
  options: UploadMediaOptions = {}
): Promise<MemoryMedia> {
  const basePath = `/api/public-shares/${encodeURIComponent(shareId)}/memories/${encodeURIComponent(memoryId)}/media/uploads`
  return uploadChunkedMedia<PublicShareMediaSummary>(
    basePath,
    request,
    options,
  ).then(normalizePublicShareMedia)
}

export function setPublicShareMemoryCover(
  shareId: string,
  memoryId: string,
  request: SetMemoryCoverRequest
): Promise<Memory> {
  return generatedCall(
    publicSharesApi.setPublicShareMemoryCover({
      shareId,
      memoryId,
      file: request.file || undefined,
      mediaId: request.mediaId || undefined,
      cropX: request.cropX,
      cropY: request.cropY,
      cropWidth: request.cropWidth,
      cropHeight: request.cropHeight
    })
  ).then(normalizePublicShareMemory)
}

export function removePublicShareMemoryCover(shareId: string, memoryId: string): Promise<Memory> {
  return generatedCall(publicSharesApi.removePublicShareMemoryCover({ shareId, memoryId })).then(
    normalizePublicShareMemory
  )
}

export function updatePublicShareMediaMetadata(
  shareId: string,
  memoryId: string,
  mediaId: string,
  request: UpdateMemoryMediaMetadataRequest
): Promise<MemoryMedia> {
  return generatedCall(
    publicSharesApi.updatePublicShareMediaMetadata({
      shareId,
      memoryId,
      mediaId,
      updateMemoryMediaMetadataRequest: request as GeneratedUpdateMemoryMediaMetadataRequest
    })
  ).then(normalizePublicShareMedia)
}

export function deletePublicShareMedia(
  shareId: string,
  memoryId: string,
  mediaId: string
): Promise<void> {
  return generatedCall(publicSharesApi.deletePublicShareMedia({ shareId, memoryId, mediaId }))
}

export function movePublicShareMemoryMedia(
  shareId: string,
  memoryId: string,
  request: FeatureMoveMemoryMediaRequest
): Promise<MemoryMedia[]> {
  return generatedCall(
    publicSharesApi.movePublicShareMemoryMedia({
      shareId,
      memoryId,
      moveMemoryMediaRequest: request as MoveMemoryMediaRequest
    })
  ).then((response) => response.media.map(normalizeMemoryMedia))
}

export function deletePublicShareMemoryMediaBatch(
  shareId: string,
  memoryId: string,
  request: FeatureDeleteMemoryMediaBatchRequest
): Promise<void> {
  return generatedCall(
    publicSharesApi.deletePublicShareMemoryMediaBatch({
      shareId,
      memoryId,
      deleteMemoryMediaBatchRequest: request as DeleteMemoryMediaBatchRequest
    })
  )
}

export function abortMemoryMediaUploadSession(
  tenantName: string,
  memoryId: string,
  uploadId: string
): Promise<void> {
  return callApi<void>(
    `/api/${encodeURIComponent(tenantName)}/memories/${encodeURIComponent(memoryId)}/media/uploads/${encodeURIComponent(uploadId)}`,
    { method: 'DELETE' }
  )
}

export function abortPublicShareMediaUploadSession(shareId: string, uploadId: string): Promise<void> {
  return callApi<void>(
    `/api/public-shares/${encodeURIComponent(shareId)}/media/uploads/${encodeURIComponent(uploadId)}`,
    { method: 'DELETE' }
  )
}

export function abortPublicShareMemoryMediaUploadSession(
  shareId: string,
  memoryId: string,
  uploadId: string
): Promise<void> {
  return callApi<void>(
    `/api/public-shares/${encodeURIComponent(shareId)}/memories/${encodeURIComponent(memoryId)}/media/uploads/${encodeURIComponent(uploadId)}`,
    { method: 'DELETE' }
  )
}

async function uploadChunkedMedia<T>(
  basePath: string,
  request: { file: File; title?: string | null },
  options: UploadMediaOptions
): Promise<T> {
  const session = await callApi<MemoryMediaUploadSession>(basePath, {
    method: 'POST',
    body: JSON.stringify(createUploadSessionRequest(request))
  })
  options.onSession?.(session)

  const pendingSession = isPendingUploadSession(session)
    ? session
    : await callApi<PendingMemoryMediaUploadSession>(`${basePath}/${encodeURIComponent(session.uploadId)}`)
  options.onSession?.(pendingSession)

  const uploadedChunks = new Set(pendingSession.uploadedChunks)
  let uploadedBytes = pendingSession.uploadedBytes
  options.onProgress?.({ loaded: uploadedBytes, total: request.file.size })

  for (let start = 0, index = 0; start < request.file.size; start += pendingSession.chunkSizeBytes, index += 1) {
    const end = Math.min(start + pendingSession.chunkSizeBytes, request.file.size)
    if (uploadedChunks.has(index)) {
      uploadedBytes = Math.max(uploadedBytes, end)
      options.onProgress?.({ loaded: uploadedBytes, total: request.file.size })
      continue
    }

    const chunk = request.file.slice(start, end)
    await uploadBlob(
      `${basePath}/${encodeURIComponent(session.uploadId)}/chunks/${index}`,
      chunk,
      'application/octet-stream',
      ({ loaded }) => {
        options.onProgress?.({
          loaded: Math.min(start + loaded, request.file.size),
          total: request.file.size
        })
      },
      options.signal
    )
    uploadedBytes = end
    options.onProgress?.({ loaded: uploadedBytes, total: request.file.size })
  }

  return callApi<T>(`${basePath}/${encodeURIComponent(session.uploadId)}/complete`, { method: 'POST' })
}

function createUploadSessionRequest(request: { file: File; title?: string | null; bucketId?: string | null }): CreateMemoryMediaUploadRequest {
  return {
    filename: request.file.name || 'Upload',
    mimeType: request.file.type || 'application/octet-stream',
    sizeBytes: request.file.size,
    title: request.title ?? undefined,
    bucketId: request.bucketId ?? undefined
  }
}

function isPendingUploadSession(
  session: MemoryMediaUploadSession | PendingMemoryMediaUploadSession
): session is PendingMemoryMediaUploadSession {
  return 'uploadedChunks' in session
}

function createMemoryRequest(request: CreateMemoryRequest): GeneratedCreateMemoryRequest {
  return {
    name: request.name,
    displayName: request.displayName,
    startDate: request.startDate,
    endDate: request.endDate,
    parentId: request.parentId ?? undefined,
    folderPath: request.folderPath ?? undefined,
    scope: request.scope ?? undefined
  }
}

function createFolderRequest(request: CreateFolderRequest): GeneratedCreateFolderRequest {
  return {
    name: request.name,
    displayName: request.displayName,
    parentPath: request.parentPath ?? undefined,
    scope: request.scope ?? undefined
  }
}

function updateMemoryRequest(request: UpdateMemoryRequest): GeneratedUpdateMemoryRequest {
  return {
    parentId: request.parentId ?? undefined,
    name: request.name ?? undefined,
    displayName: request.displayName ?? undefined,
    startDate: request.startDate ?? undefined,
    endDate: request.endDate ?? undefined
  }
}

function createMemoryPlaylistRequest(request: CreateMemoryPlaylistRequest): GeneratedCreateMemoryPlaylistRequest {
  return {
    title: request.title ?? undefined,
    note: request.note ?? undefined,
    featured: request.featured,
    url: request.url
  }
}

function createPublicShareMemoryRequest(request: CreateMemoryRequest) {
  return {
    name: request.name,
    displayName: request.displayName,
    startDate: request.startDate,
    endDate: request.endDate
  }
}

function updatePublicShareMemoryRequest(request: UpdateMemoryRequest) {
  return {
    name: request.name ?? undefined,
    displayName: request.displayName ?? undefined,
    startDate: request.startDate ?? undefined,
    endDate: request.endDate ?? undefined
  }
}

function normalizeMemory(
  memory: {
    parentId?: string
    coverMediaId?: string
    coverUrl?: string
    coverCrop?: Memory['coverCrop']
    scope?: Memory['scope']
    favorite?: boolean
  } & Omit<Memory, 'parentId' | 'coverMediaId' | 'coverUrl' | 'coverCrop' | 'scope' | 'favorite'>
): Memory {
  return {
    ...memory,
    parentId: memory.parentId ?? null,
    coverMediaId: memory.coverMediaId ?? null,
    coverUrl: memory.coverUrl ?? null,
    coverCrop: memory.coverCrop ?? null,
    favorite: memory.favorite ?? false,
    scope: memory.scope ?? null
  }
}

function normalizeMemoryHeader(
  header: {
    coverUrl?: string
    coverCrop?: MemoryHeader['coverCrop']
    scope?: MemoryHeader['scope']
    favorite?: boolean
    participants?: MemoryHeader['participants']
  } & Omit<MemoryHeader, 'coverUrl' | 'coverCrop' | 'scope' | 'favorite' | 'participants'>
): MemoryHeader {
  return {
    ...header,
    coverUrl: header.coverUrl ?? null,
    coverCrop: header.coverCrop ?? null,
    favorite: header.favorite ?? false,
    scope: header.scope ?? null,
    participants: header.participants ?? []
  }
}

function normalizeMemoryMedia(
  media: {
    capturedAt?: string
    thumbnailUrl?: string
    previewUrl?: string
    cameraMake?: string
    cameraModel?: string
    lensModel?: string
    imageWidth?: number
    imageHeight?: number
    gpsLatitude?: number
    gpsLongitude?: number
  } & Omit<
    MemoryMedia,
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
  >
): MemoryMedia {
  return {
    ...media,
    capturedAt: media.capturedAt ?? null,
    thumbnailUrl: media.thumbnailUrl ?? null,
    previewUrl: media.previewUrl ?? null,
    cameraMake: media.cameraMake ?? null,
    cameraModel: media.cameraModel ?? null,
    lensModel: media.lensModel ?? null,
    imageWidth: media.imageWidth ?? null,
    imageHeight: media.imageHeight ?? null,
    gpsLatitude: media.gpsLatitude ?? null,
    gpsLongitude: media.gpsLongitude ?? null
  }
}

function normalizeMemoryTrack(track: MemoryTrackSummary): MemoryTrack {
  return track
}

function normalizeMemoryPlaylist(playlist: MemoryPlaylistSummary): MemoryPlaylist {
  return {
    ...playlist,
    note: playlist.note ?? undefined,
    featured: playlist.featured ?? false
  }
}

function normalizePublicShareMemory(memory: PublicShareMemorySummary): Memory {
  return normalizeMemory({
    ...memory,
    parentId: memory.parentId || undefined,
    coverMediaId: memory.coverMediaId,
    coverUrl: memory.coverUrl,
    coverCrop: memory.coverCrop,
    favorite: false,
    scope: undefined
  })
}

function normalizePublicShareMemoryHeader(header: PublicShareMemoryHeaderSummary): MemoryHeader {
  return normalizeMemoryHeader({
    ...header,
    coverUrl: header.coverUrl,
    coverCrop: header.coverCrop,
    favorite: false,
    scope: null
  })
}

function normalizePublicShareMedia(media: PublicShareMediaSummary): MemoryMedia {
  return normalizeMemoryMedia({
    ...media,
    capturedAt: media.capturedAt ?? undefined,
    thumbnailUrl: media.thumbnailUrl ?? undefined,
    previewUrl: media.previewUrl ?? undefined,
    cameraMake: undefined,
    cameraModel: undefined,
    lensModel: undefined,
    imageWidth: undefined,
    imageHeight: undefined,
    gpsLatitude: media.gpsLatitude ?? undefined,
    gpsLongitude: media.gpsLongitude ?? undefined
  })
}
