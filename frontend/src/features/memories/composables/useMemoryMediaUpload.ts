import { i18n } from '../../../i18n'
import { uploadMemoryMedia, type MemoryMedia, type UploadMediaOptions, type UploadMemoryMediaRequest } from '../api'
import { startUploadBatch } from './useUploadQueue'

type StartMemoryMediaUploadRequest = {
  tenantName: string
  memoryId: string
  files: File[]
  bucketId?: string | null
  uploadMedia?: (
    memoryId: string,
    request: UploadMemoryMediaRequest,
    options?: UploadMediaOptions
  ) => Promise<MemoryMedia>
  onFileComplete?: (media: MemoryMedia) => void
  onComplete?: () => void
}

export function mediaFilesFromList(files: FileList | File[]): File[] {
  return Array.from(files).filter(isSupportedMediaFile)
}

export function isSupportedMediaFile(file: File): boolean {
  return file.type.startsWith('image/') || file.type.startsWith('video/')
}

export function startMemoryMediaUpload(request: StartMemoryMediaUploadRequest) {
  const files = mediaFilesFromList(request.files)
  if (!files.length) return null

  return startUploadBatch({
    label: i18n.global.t('media.uploadBatchTitle', { count: files.length }),
    files,
    context: { type: 'memory', tenantName: request.tenantName, memoryId: request.memoryId },
    uploadFile: (file, options) => {
      const uploadRequest: UploadMemoryMediaRequest = { file, title: file.name }
      if (request.bucketId) uploadRequest.bucketId = request.bucketId
      return request.uploadMedia
        ? request.uploadMedia(request.memoryId, uploadRequest, options)
        : uploadMemoryMedia(request.tenantName, request.memoryId, uploadRequest, options)
    },
    onFileComplete: (_file, result) => {
      request.onFileComplete?.(result as MemoryMedia)
    },
    onComplete: request.onComplete
  })
}
