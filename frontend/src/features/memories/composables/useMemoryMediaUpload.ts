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

type FileUploadSelectLikeEvent = {
  originalEvent?: Event
  files: FileList | File[]
}

const supportedMediaExtensions = new Set([
  'jpg',
  'jpeg',
  'png',
  'gif',
  'webp',
  'avif',
  'heic',
  'heif',
  'tif',
  'tiff',
  'bmp',
  'mov',
  'mp4',
  'm4v',
  'webm',
  'avi',
  'mkv'
])

export const mediaFileAccept = [
  'image/*',
  'video/*',
  ...Array.from(supportedMediaExtensions).map((extension) => `.${extension}`)
].join(',')

export function mediaFilesFromList(files: FileList | File[]): File[] {
  return Array.from(files).filter(isSupportedMediaFile)
}

export function mediaFilesFromFileUploadSelectEvent(event: FileUploadSelectLikeEvent): File[] {
  const browserFiles = filesFromBrowserEvent(event.originalEvent)
  const eventFiles = Array.from(event.files)
  const selectedFiles = browserFiles.length > eventFiles.length ? browserFiles : eventFiles
  return mediaFilesFromList(selectedFiles)
}

export function isSupportedMediaFile(file: File): boolean {
  if (file.type.startsWith('image/') || file.type.startsWith('video/')) return true
  const extension = file.name.split('.').pop()?.toLowerCase()
  return Boolean(extension && supportedMediaExtensions.has(extension))
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

function filesFromBrowserEvent(event?: Event): File[] {
  const targetFiles = (event?.target as HTMLInputElement | null)?.files
  if (targetFiles) return Array.from(targetFiles)

  const droppedFiles = (event as DragEvent | undefined)?.dataTransfer?.files
  return droppedFiles ? Array.from(droppedFiles) : []
}
