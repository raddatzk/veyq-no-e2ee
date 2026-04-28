import {
  completeMediaUpload,
  createMediaUpload,
  uploadMediaChunk,
  type ExplorerMedia,
  type UploadMediaRequest
} from '../api'

export async function uploadMedia(
  tenantName: string,
  request: UploadMediaRequest
): Promise<ExplorerMedia> {
  const session = await createMediaUpload(tenantName, request)
  const chunkSize = session.chunkSizeBytes

  for (let start = 0, index = 0; start < request.file.size; start += chunkSize, index += 1) {
    const end = Math.min(start + chunkSize, request.file.size)
    await uploadMediaChunk(tenantName, session.uploadId, index, request.file.slice(start, end))
    request.onProgress?.(end, request.file.size)
  }

  return completeMediaUpload(tenantName, session.uploadId)
}
