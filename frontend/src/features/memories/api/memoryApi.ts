import {
  foldersApi,
  generatedCall,
  memoriesApi,
  memoryMediaApi,
  memoryNavigationApi,
  memorySharesApi,
  publicSharesApi
} from '../../../api/generatedClient'
import type {
  CreateFolderRequest as GeneratedCreateFolderRequest,
  CreateMemoryRequest as GeneratedCreateMemoryRequest,
  CreateMemoryShareRequest as GeneratedCreateMemoryShareRequest,
  CreatePublicShareMemoryRequest as GeneratedCreatePublicShareMemoryRequest,
  MemoryShareRole,
  PublicShareMediaSummary,
  PublicShareMemorySummary,
  UpdateMemoryMediaMetadataRequest as GeneratedUpdateMemoryMediaMetadataRequest,
  UpdateMemoryRequest as GeneratedUpdateMemoryRequest,
  UpdatePublicShareMemoryRequest as GeneratedUpdatePublicShareMemoryRequest
} from '../../../api/generated'
import type {
  CreateFolderRequest,
  CreateMemoryRequest,
  Memory,
  MemoryFolder,
  MemoryMedia,
  MemoryNavigationResponse,
  SetMemoryCoverRequest,
  UpdateMemoryMediaMetadataRequest,
  UpdateMemoryRequest,
  UploadMemoryMediaRequest
} from './types'

export function getMemoryNavigation(
  tenantName: string,
  path: string
): Promise<MemoryNavigationResponse> {
  return generatedCall(memoryNavigationApi.getMemoryNavigation({ tenantName, path }))
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

export function listChildMemories(tenantName: string, memoryId: string): Promise<Memory[]> {
  return generatedCall(memoriesApi.listChildMemories({ tenantName, memoryId })).then((memories) =>
    memories.map(normalizeMemory)
  )
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

export function uploadMemoryMedia(
  tenantName: string,
  memoryId: string,
  request: UploadMemoryMediaRequest
): Promise<MemoryMedia> {
  return generatedCall(
    memoryMediaApi.uploadMemoryMedia({
      tenantName,
      memoryId,
      file: request.file,
      title: request.title ?? undefined
    })
  ).then(normalizeMemoryMedia)
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

export function listMemoryShares(tenantName: string, memoryId: string) {
  return generatedCall(memorySharesApi.listMemoryShares({ tenantName, memoryId }))
}

export function createMemoryShare(
  tenantName: string,
  memoryId: string,
  request: { name?: string | null; role: MemoryShareRole; password?: string | null }
) {
  const createMemoryShareRequest = {
    name: request.name,
    role: request.role,
    password: request.password
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
  request: { name?: string | null; role?: MemoryShareRole; enabled?: boolean; password?: string | null }
) {
  return generatedCall(
    memorySharesApi.updateMemoryShare({
      tenantName,
      shareId,
      updateMemoryShareRequest: {
        name: request.name === null ? '' : request.name,
        role: request.role,
        enabled: request.enabled,
        password: request.password === null ? '' : request.password
      }
    })
  )
}

export function getPublicShare(shareId: string) {
  return generatedCall(publicSharesApi.getPublicShare({ shareId }))
}

export function loginPublicShare(shareId: string, password: string | null, displayName: string) {
  return generatedCall(
    publicSharesApi.loginPublicShare({
      shareId,
      publicShareLoginRequest: { password: password ?? undefined, displayName }
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
  request: { file: File; title?: string | null }
) {
  return generatedCall(
    publicSharesApi.uploadPublicShareMedia({
      shareId,
      file: request.file,
      title: request.title ?? undefined
    })
  ).then(normalizePublicShareMedia)
}

export function getPublicShareMemoryByPath(shareId: string, path: string): Promise<Memory> {
  return generatedCall(publicSharesApi.getPublicShareMemoryByPath({ shareId, path })).then(
    normalizePublicShareMemory
  )
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

export function listPublicShareMemoryMedia(
  shareId: string,
  memoryId: string
): Promise<MemoryMedia[]> {
  return generatedCall(publicSharesApi.listPublicShareMemoryMedia({ shareId, memoryId })).then(
    (media) => media.map(normalizePublicShareMedia)
  )
}

export function uploadPublicShareMemoryMedia(
  shareId: string,
  memoryId: string,
  request: UploadMemoryMediaRequest
): Promise<MemoryMedia> {
  return generatedCall(
    publicSharesApi.uploadPublicShareMemoryMedia({
      shareId,
      memoryId,
      file: request.file,
      title: request.title ?? undefined
    })
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
  } & Omit<Memory, 'parentId' | 'coverMediaId' | 'coverUrl' | 'coverCrop' | 'scope'>
): Memory {
  return {
    ...memory,
    parentId: memory.parentId ?? null,
    coverMediaId: memory.coverMediaId ?? null,
    coverUrl: memory.coverUrl ?? null,
    coverCrop: memory.coverCrop ?? null,
    scope: memory.scope ?? null
  }
}

function normalizeMemoryMedia(
  media: {
    capturedAt?: string
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

function normalizePublicShareMemory(memory: PublicShareMemorySummary): Memory {
  return normalizeMemory({
    ...memory,
    parentId: memory.parentId || undefined,
    coverMediaId: memory.coverMediaId,
    coverUrl: memory.coverUrl,
    coverCrop: memory.coverCrop,
    scope: undefined
  })
}

function normalizePublicShareMedia(media: PublicShareMediaSummary): MemoryMedia {
  return normalizeMemoryMedia({
    ...media,
    capturedAt: media.capturedAt ?? undefined,
    previewUrl: media.previewUrl ?? undefined,
    cameraMake: undefined,
    cameraModel: undefined,
    lensModel: undefined,
    imageWidth: undefined,
    imageHeight: undefined,
    gpsLatitude: undefined,
    gpsLongitude: undefined
  })
}
