import { generatedCall, memoriesApi, memoryMediaApi } from '../../../api/generatedClient'
import type {
  CreateMemoryRequest as GeneratedCreateMemoryRequest,
  SetMemoryCoverRequest as GeneratedSetMemoryCoverRequest,
  UpdateMemoryMediaMetadataRequest as GeneratedUpdateMemoryMediaMetadataRequest,
  UpdateMemoryRequest as GeneratedUpdateMemoryRequest
} from '../../../api/generated'
import type {
  CreateMemoryRequest,
  Memory,
  MemoryMedia,
  SetMemoryCoverRequest,
  UpdateMemoryMediaMetadataRequest,
  UpdateMemoryRequest,
  UploadMemoryMediaRequest
} from './types'

export function listMemories(tenantName: string): Promise<Memory[]> {
  return generatedCall(memoriesApi.listMemories({ tenantName })).then((memories) =>
    memories.map(normalizeMemory)
  )
}

export function getMemory(tenantName: string, memoryId: string): Promise<Memory> {
  return generatedCall(memoriesApi.getMemory({ tenantName, memoryId })).then(normalizeMemory)
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
      setMemoryCoverRequest: request as GeneratedSetMemoryCoverRequest
    })
  ).then(normalizeMemory)
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

function createMemoryRequest(request: CreateMemoryRequest): GeneratedCreateMemoryRequest {
  return {
    name: request.name,
    displayName: request.displayName,
    parentId: request.parentId ?? undefined,
    scope: request.scope ?? undefined
  }
}

function updateMemoryRequest(request: UpdateMemoryRequest): GeneratedUpdateMemoryRequest {
  return {
    parentId: request.parentId ?? undefined,
    name: request.name ?? undefined,
    displayName: request.displayName ?? undefined
  }
}

function normalizeMemory(memory: {
  parentId?: string
  coverMediaId?: string
  scope?: Memory['scope']
} & Omit<Memory, 'parentId' | 'coverMediaId' | 'scope'>): Memory {
  return {
    ...memory,
    parentId: memory.parentId ?? null,
    coverMediaId: memory.coverMediaId ?? null,
    scope: memory.scope ?? null
  }
}

function normalizeMemoryMedia(media: {
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
>): MemoryMedia {
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

