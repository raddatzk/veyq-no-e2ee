import {
  generatedCall,
  mediaApi as generatedMediaApi,
  mediaUploadsApi
} from '../../../api/generatedClient'
import type {
  MediaSummary,
  PendingMediaUploadSummary,
  UpdateMediaMetadataRequest as GeneratedUpdateMediaMetadataRequest
} from '../../../api/generated'
import { apiFetch } from '../../../shared/api/httpClient'
import { pathQuery, tenantApiPath } from '../../../shared/utils/paths'
import type {
  ExplorerMedia,
  MediaUploadSession,
  PendingMediaUploadSession,
  StreamMediaOptions,
  UpdateMediaMetadataRequest,
  UploadMediaRequest
} from './types'
export async function streamExplorerMediaMetadata(
  tenantName: string,
  path: string,
  onBatch: (items: ExplorerMedia[]) => void,
  options: StreamMediaOptions = {}
): Promise<void> {
  const response = await apiFetch(tenantApiPath(tenantName, 'media', `?${pathQuery(path)}`), {
    signal: options.signal,
    headers: { Accept: 'application/x-ndjson' }
  })

  if (!response.ok) {
    const body = await response
      .json()
      .catch(() => ({ error: 'Medien konnten nicht geladen werden' }))
    throw new Error(body.error || 'Medien konnten nicht geladen werden')
  }

  const emit = createMetadataBatchEmitter(onBatch, options)

  if (!response.body) {
    const text = await response.text()
    parseNdjson(text, emit.push)
    emit.flush()
    return
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''

  while (true) {
    const { value, done } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    const lines = buffer.split('\n')
    buffer = lines.pop() || ''
    lines.forEach((line) => parseLine(line, emit.push))
  }

  buffer += decoder.decode()
  parseLine(buffer, emit.push)
  emit.flush()
}

export async function createMediaUpload(
  tenantName: string,
  request: UploadMediaRequest
): Promise<MediaUploadSession> {
  return generatedCall(
    mediaUploadsApi.createUpload({
      createMediaUploadRequest: {
        tenantName,
        albumPath: request.albumPath,
        filename: request.file.name,
        mimeType: request.file.type,
        sizeBytes: request.file.size,
        title: request.title
      }
    })
  )
}

export async function uploadMediaChunk(
  uploadId: string,
  chunkIndex: number,
  chunk: Blob,
  options: { signal?: AbortSignal } = {}
): Promise<void> {
  await generatedCall(
    mediaUploadsApi.uploadChunk(
      { uploadId, chunkIndex, body: chunk },
      { signal: options.signal }
    )
  )
}

export function completeMediaUpload(tenantName: string, uploadId: string): Promise<ExplorerMedia> {
  void tenantName
  return generatedCall(mediaUploadsApi.completeUpload({ uploadId })).then(normalizeMedia)
}

export function listMediaUploads(): Promise<PendingMediaUploadSession[]> {
  return generatedCall(mediaUploadsApi.listUploads()).then((uploads) => uploads.map(normalizeUpload))
}

export function getMediaUpload(uploadId: string): Promise<PendingMediaUploadSession> {
  return generatedCall(mediaUploadsApi.getUpload({ uploadId })).then(normalizeUpload)
}

export function abortMediaUpload(uploadId: string): Promise<void> {
  return generatedCall(mediaUploadsApi.abortUpload({ uploadId }))
}

export function updateMediaMetadata(
  tenantName: string,
  mediaId: string,
  request: UpdateMediaMetadataRequest
): Promise<ExplorerMedia> {
  return generatedCall(
    generatedMediaApi.updateMetadata({
      tenantName,
      mediaId,
      updateMediaMetadataRequest: request as GeneratedUpdateMediaMetadataRequest
    })
  ).then(normalizeMedia)
}

function parseNdjson(text: string, onItem: (item: ExplorerMedia) => void) {
  text.split('\n').forEach((line) => parseLine(line, onItem))
}

function parseLine(line: string, onItem: (item: ExplorerMedia) => void) {
  const trimmed = line.trim()
  if (!trimmed) return
  onItem(normalizeMedia(JSON.parse(trimmed) as MediaSummary))
}

function normalizeUpload(upload: PendingMediaUploadSummary): PendingMediaUploadSession {
  return {
    ...upload,
    title: upload.title ?? null
  }
}

function normalizeMedia(media: MediaSummary): ExplorerMedia {
  return {
    ...media,
    albumPath: media.albumPath ?? null,
    capturedAt: media.capturedAt ?? null,
    cameraMake: media.cameraMake ?? null,
    cameraModel: media.cameraModel ?? null,
    lensModel: media.lensModel ?? null,
    imageWidth: media.imageWidth ?? null,
    imageHeight: media.imageHeight ?? null,
    gpsLatitude: media.gpsLatitude ?? null,
    gpsLongitude: media.gpsLongitude ?? null
  }
}

function createMetadataBatchEmitter(
  onBatch: (items: ExplorerMedia[]) => void,
  options: StreamMediaOptions
) {
  const batchSize = options.batchSize ?? 32
  let batch: ExplorerMedia[] = []
  let frame: number | null = null

  function push(item: ExplorerMedia) {
    if (options.signal?.aborted) return

    batch.push(item)
    if (batch.length >= batchSize) {
      cancelScheduledFlush()
      flush()
    } else {
      scheduleFlush()
    }
  }

  function scheduleFlush() {
    if (frame !== null || !batch.length) return
    if (typeof requestAnimationFrame === 'undefined') return

    frame = requestAnimationFrame(() => {
      frame = null
      flush()
    })
  }

  function flush() {
    if (frame !== null) cancelScheduledFlush()
    if (options.signal?.aborted || !batch.length) return

    const items = batch
    batch = []
    onBatch(items)
  }

  function cancelScheduledFlush() {
    if (frame === null || typeof cancelAnimationFrame === 'undefined') {
      frame = null
      return
    }
    cancelAnimationFrame(frame)
    frame = null
  }

  return { push, flush }
}
