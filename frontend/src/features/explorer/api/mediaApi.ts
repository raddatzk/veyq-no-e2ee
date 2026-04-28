import { apiFetch, callApi } from '../../../shared/api/httpClient'
import { pathQuery, tenantApiPath } from '../../../shared/utils/paths'
import type {
  ExplorerMedia,
  MediaUploadSession,
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
  return callApi(tenantApiPath(tenantName, 'media', '/uploads'), {
    method: 'POST',
    body: JSON.stringify({
      albumPath: request.albumPath,
      filename: request.file.name,
      mimeType: request.file.type,
      sizeBytes: request.file.size,
      title: request.title
    })
  })
}

export async function uploadMediaChunk(
  tenantName: string,
  uploadId: string,
  chunkIndex: number,
  chunk: Blob
): Promise<void> {
  const response = await apiFetch(
    tenantApiPath(
      tenantName,
      'media',
      `/uploads/${encodeURIComponent(uploadId)}/chunks/${chunkIndex}`
    ),
    {
      method: 'PUT',
      headers: { 'Content-Type': 'application/octet-stream' },
      body: chunk
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
  return callApi(
    tenantApiPath(tenantName, 'media', `/uploads/${encodeURIComponent(uploadId)}/complete`),
    {
      method: 'POST'
    }
  )
}

function parseNdjson(text: string, onItem: (item: ExplorerMedia) => void) {
  text.split('\n').forEach((line) => parseLine(line, onItem))
}

function parseLine(line: string, onItem: (item: ExplorerMedia) => void) {
  const trimmed = line.trim()
  if (!trimmed) return
  onItem(JSON.parse(trimmed) as ExplorerMedia)
}
