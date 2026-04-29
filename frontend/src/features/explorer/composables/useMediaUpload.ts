import {
  completeMediaUpload,
  createMediaUpload,
  getMediaUpload,
  uploadMediaChunk,
  type ExplorerMedia,
  type MediaUploadSession,
  type PendingMediaUploadSession,
  type UploadMediaRequest
} from '../api'

export async function uploadMedia(
  tenantName: string,
  request: UploadMediaRequest
): Promise<ExplorerMedia> {
  const session: MediaUploadSession | PendingMediaUploadSession = request.existingUploadId
    ? await getMediaUpload(request.existingUploadId)
    : await createMediaUpload(tenantName, request)
  const chunkSize = session.chunkSizeBytes
  const pendingSession = isPendingUploadSession(session) ? session : null
  const uploadedChunks = new Set<number>(pendingSession?.uploadedChunks || [])

  request.onSession?.(session)
  if (pendingSession) {
    request.onProgress?.(pendingSession.uploadedBytes, request.file.size)
  }

  for (let start = 0, index = 0; start < request.file.size; start += chunkSize, index += 1) {
    const end = Math.min(start + chunkSize, request.file.size)
    if (uploadedChunks.has(index)) {
      request.onProgress?.(end, request.file.size)
      continue
    }
    await uploadMediaChunk(session.uploadId, index, request.file.slice(start, end), {
      signal: request.signal
    })
    request.onProgress?.(end, request.file.size)
  }

  return completeMediaUpload(tenantName, session.uploadId)
}

function isPendingUploadSession(
  session: MediaUploadSession | PendingMediaUploadSession
): session is PendingMediaUploadSession {
  return 'uploadedChunks' in session
}
