import { apiFetch, callApi } from '../../../shared/api/httpClient'
import { pathQuery, tenantApiPath } from '../../../shared/utils/paths'
import type {
  ExplorerMedia,
  MediaUploadSession,
  PendingMediaUploadSession,
  StreamMediaOptions,
  UploadMediaRequest
} from './types'

export async function streamExplorerMedia(
  tenantName: string,
  path: string,
  onItem: (item: ExplorerMedia) => void,
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

  if (!response.body) {
    const text = await response.text()
    parseNdjson(text, onItem)
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
    lines.forEach((line) => parseLine(line, onItem))
  }

  buffer += decoder.decode()
  parseLine(buffer, onItem)
}

export async function createMediaUpload(
  tenantName: string,
  request: UploadMediaRequest
): Promise<MediaUploadSession> {
  return callApi<MediaUploadSession>('/api/media/uploads', {
    method: 'POST',
    body: JSON.stringify({
      tenantName,
      albumPath: request.albumPath,
      filename: request.file.name,
      mimeType: request.file.type,
      sizeBytes: request.file.size,
      title: request.title
    })
  })
}

export async function uploadMediaChunk(
  uploadId: string,
  chunkIndex: number,
  chunk: Blob,
  options: { signal?: AbortSignal } = {}
): Promise<void> {
  const response = await apiFetch(
    `/api/media/uploads/${encodeURIComponent(uploadId)}/chunks/${chunkIndex}`,
    {
      method: 'PUT',
      headers: { 'Content-Type': 'application/octet-stream' },
      body: chunk,
      signal: options.signal
    }
  )

  if (!response.ok) {
    const body = await response
      .json()
      .catch(() => ({ error: 'Chunk konnte nicht hochgeladen werden' }))
    throw new Error(body.error || 'Chunk konnte nicht hochgeladen werden')
  }
}

export function completeMediaUpload(tenantName: string, uploadId: string): Promise<ExplorerMedia> {
  void tenantName
  return callApi<ExplorerMedia>(`/api/media/uploads/${encodeURIComponent(uploadId)}/complete`, {
    method: 'POST'
  })
}

export function listMediaUploads(): Promise<PendingMediaUploadSession[]> {
  return callApi<PendingMediaUploadSession[]>('/api/media/uploads')
}

export function getMediaUpload(uploadId: string): Promise<PendingMediaUploadSession> {
  return callApi<PendingMediaUploadSession>(`/api/media/uploads/${encodeURIComponent(uploadId)}`)
}

export function abortMediaUpload(uploadId: string): Promise<void> {
  return callApi<void>(`/api/media/uploads/${encodeURIComponent(uploadId)}`, {
    method: 'DELETE'
  })
}

function parseNdjson(text: string, onItem: (item: ExplorerMedia) => void) {
  text.split('\n').forEach((line) => parseLine(line, onItem))
}

function parseLine(line: string, onItem: (item: ExplorerMedia) => void) {
  const trimmed = line.trim()
  if (!trimmed) return
  onItem(JSON.parse(trimmed) as ExplorerMedia)
}
