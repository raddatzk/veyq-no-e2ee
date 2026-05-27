import { computed, reactive, ref } from 'vue'
import { i18n } from '../../../i18n'
import { UploadCancelledError } from '../../../shared/api/httpClient'
import {
  abortMemoryMediaUploadSession,
  abortPublicShareMediaUploadSession,
  abortPublicShareMemoryMediaUploadSession,
  uploadMemoryMedia,
  uploadPublicShareMedia,
  uploadPublicShareMemoryMedia,
  type UploadMediaOptions
} from '../api'

export type UploadFileStatus = 'queued' | 'uploading' | 'done' | 'failed' | 'cancelled'

export type UploadQueueFile = {
  id: string
  file: File
  name: string
  sizeBytes: number
  loadedBytes: number
  status: UploadFileStatus
  error: string
  uploadId: string | null
  controller: AbortController
}

export type UploadQueueContext =
  | { type: 'memory'; tenantName: string; memoryId: string }
  | { type: 'public-share-root'; shareId: string }
  | { type: 'public-share-memory'; shareId: string; memoryId: string }

export type UploadQueueBatch = {
  id: string
  label: string
  context: UploadQueueContext
  files: UploadQueueFile[]
  startedAt: number
  sequence: number
  finishedAt: number | null
  acknowledged: boolean
  uploadFile?: (file: File, options: UploadMediaOptions) => Promise<unknown>
  onFileComplete?: (file: UploadQueueFile, result: unknown) => void | Promise<void>
  onComplete?: () => void | Promise<void>
}

type StartUploadBatchRequest = {
  label: string
  files: File[]
  context: UploadQueueContext
  uploadFile?: (file: File, options: UploadMediaOptions) => Promise<unknown>
  onFileComplete?: (file: UploadQueueFile, result: unknown) => void | Promise<void>
  onComplete?: () => void | Promise<void>
}

const batches = reactive<UploadQueueBatch[]>([])
const queueRevision = ref(0)
let runnerRequest: Promise<void> | null = null
let batchSequence = 0

const activeBatches = computed(() => {
  trackQueueRevision()
  return batches.filter((batch) => batch.files.some((file) => isActiveFileStatus(file.status)))
})
const visibleBatches = computed(() => {
  trackQueueRevision()
  return batches.filter((batch) => !batch.acknowledged)
})
const activeFileCount = computed(() => {
  trackQueueRevision()
  return batches.flatMap((batch) => batch.files).filter((file) => isActiveFileStatus(file.status)).length
})
const hasVisibleUploads = computed(() => visibleBatches.value.length > 0)

export function useUploadQueue() {
  return {
    batches,
    activeBatches,
    visibleBatches,
    activeFileCount,
    queueRevision,
    hasVisibleUploads,
    startUploadBatch,
    cancelFile,
    cancelBatch,
    cancelAll,
    acknowledgeFinished,
    clearUploadQueue,
    batchProgress,
    fileProgress,
    isBatchFinished
  }
}

export function startUploadBatch(request: StartUploadBatchRequest): UploadQueueBatch {
  const batch: UploadQueueBatch = {
    id: uploadQueueId(),
    label: request.label,
    context: request.context,
    files: request.files.map((file) => ({
      id: uploadQueueId(),
      file,
      name: file.name,
      sizeBytes: file.size,
      loadedBytes: 0,
      status: 'queued',
      error: '',
      uploadId: null,
      controller: new AbortController()
    })),
    startedAt: Date.now(),
    sequence: ++batchSequence,
    finishedAt: null,
    acknowledged: false,
    uploadFile: request.uploadFile,
    onFileComplete: request.onFileComplete,
    onComplete: request.onComplete
  }

  batches.unshift(batch)
  touchQueue()
  scheduleRunner()
  return batches[0]
}

export function cancelFile(file: UploadQueueFile) {
  if (file.status === 'queued') {
    setFileProgress(file, 0)
    setFileStatus(file, 'cancelled')
    void abortServerUpload(file)
    return
  }
  if (file.status === 'uploading') {
    file.controller.abort()
    setFileProgress(file, 0)
    setFileStatus(file, 'cancelled')
    void abortServerUpload(file)
  }
}

export function cancelBatch(batch: UploadQueueBatch) {
  batch.files.forEach(cancelFile)
}

export function cancelAll() {
  batches.forEach(cancelBatch)
}

export function acknowledgeFinished(batch?: UploadQueueBatch) {
  const finished = batch ? [batch] : batches.filter(isBatchFinished)
  finished.forEach((item) => {
    item.acknowledged = true
    touchQueue()
  })
}

export function clearUploadQueue() {
  batches.forEach((batch) => {
    batch.files.forEach((file) => {
      if (file.status === 'uploading') file.controller.abort()
    })
  })
  batches.splice(0, batches.length)
  touchQueue()
}

export function batchProgress(batch: UploadQueueBatch): number {
  trackQueueRevision()
  const total = batch.files.reduce((sum, file) => sum + file.sizeBytes, 0)
  if (total <= 0) {
    const completed = batch.files.filter((file) => file.status === 'done').length
    return Math.round((completed / Math.max(batch.files.length, 1)) * 100)
  }

  const loaded = batch.files.reduce((sum, file) => {
    if (file.status === 'done') return sum + file.sizeBytes
    return sum + Math.min(file.loadedBytes, file.sizeBytes)
  }, 0)
  return Math.min(100, Math.round((loaded / total) * 100))
}

export function fileProgress(file: UploadQueueFile): number {
  trackQueueRevision()
  if (file.status === 'done') return 100
  if (file.sizeBytes <= 0) return file.status === 'uploading' ? 100 : 0
  return Math.min(100, Math.round((Math.min(file.loadedBytes, file.sizeBytes) / file.sizeBytes) * 100))
}

export function isBatchFinished(batch: UploadQueueBatch): boolean {
  return batch.files.every((file) => file.status === 'done' || file.status === 'failed' || file.status === 'cancelled')
}

function scheduleRunner() {
  if (runnerRequest) return
  runnerRequest = runGlobalQueue().finally(() => {
    runnerRequest = null
    if (findNextQueuedUpload()) {
      scheduleRunner()
    }
  })
}

async function runGlobalQueue() {
  while (true) {
    const next = findNextQueuedUpload()
    if (!next) return
    const result = await uploadQueueFile(next.batch, next.file, next.uploadFile)
    if (result === 'interrupted') return
  }
}

function findNextQueuedUpload():
  | {
      batch: UploadQueueBatch
      file: UploadQueueFile
      uploadFile: (file: File, options: UploadMediaOptions) => Promise<unknown>
    }
  | null {
  for (const batch of orderedBatches()) {
    const file = batch.files.find((candidate) => candidate.status === 'queued')
    if (!file) continue
    return {
      batch,
      file,
      uploadFile: batch.uploadFile || defaultUploadFileForBatch(batch)
    }
  }
  return null
}

async function uploadQueueFile(
  batch: UploadQueueBatch,
  queueFile: UploadQueueFile,
  uploadFile: (file: File, options: UploadMediaOptions) => Promise<unknown>
): Promise<'completed' | 'interrupted'> {
  if (queueFile.status !== 'queued') return 'completed'
  setFileStatus(queueFile, 'uploading')
  setFileProgress(queueFile, 0)
  setFileError(queueFile, '')

  try {
    if (queueFile.controller.signal.aborted) {
      setFileProgress(queueFile, 0)
      setFileStatus(queueFile, 'cancelled')
      await finalizeBatch(batch)
      return 'completed'
    }
    const result = await uploadFile(queueFile.file, {
      signal: queueFile.controller.signal,
      onSession: (session) => {
        setFileSession(queueFile, session.uploadId)
        if ('uploadedBytes' in session) {
          setFileProgress(queueFile, Math.min(session.uploadedBytes, queueFile.sizeBytes))
        }
      },
      onProgress: ({ loaded }) => {
        setFileProgress(queueFile, Math.min(loaded, queueFile.sizeBytes))
      }
    })
    setFileProgress(queueFile, queueFile.sizeBytes)
    setFileStatus(queueFile, 'done')
    await batch.onFileComplete?.(queueFile, result)
  } catch (error) {
    if (error instanceof UploadCancelledError) {
      setFileProgress(queueFile, 0)
      setFileStatus(queueFile, 'cancelled')
      await finalizeBatch(batch)
      return 'completed'
    }

    if (isRecoverableUploadInterruption(error)) {
      setFileStatus(queueFile, 'queued')
      setFileError(queueFile, '')
      return 'interrupted'
    }

    setFileStatus(queueFile, 'failed', error instanceof Error ? error.message : uploadQueueMessage('media.uploadErrors.failed'))
    cancelQueuedFiles(batch)
  }

  await finalizeBatch(batch)
  return 'completed'
}

async function finalizeBatch(batch: UploadQueueBatch) {
  if (!isBatchFinished(batch)) {
    setBatchFinished(batch, null)
    return
  }

  const wasFinished = batch.finishedAt !== null
  setBatchFinished(batch, batch.finishedAt || Date.now())
  if (!wasFinished && batch.files.some((file) => file.status === 'done')) {
    await batch.onComplete?.()
  }
}

function cancelQueuedFiles(batch: UploadQueueBatch) {
  batch.files
    .filter((file) => file.status === 'queued')
    .forEach((file) => {
      setFileStatus(file, 'cancelled')
    })
}

function defaultUploadFileForBatch(batch: UploadQueueBatch) {
  return (file: File, options: UploadMediaOptions): Promise<unknown> => {
    if (batch.context.type === 'memory') {
      return uploadMemoryMedia(batch.context.tenantName, batch.context.memoryId, { file, title: file.name }, options)
    }
    if (batch.context.type === 'public-share-root') {
      return uploadPublicShareMedia(batch.context.shareId, { file, title: file.name }, options)
    }
    return uploadPublicShareMemoryMedia(batch.context.shareId, batch.context.memoryId, { file, title: file.name }, options)
  }
}

async function abortServerUpload(file: UploadQueueFile) {
  const batch = batches.find((candidate) => candidate.files.some((item) => item.id === file.id))
  if (!batch || !file.uploadId) return
  try {
    if (batch.context.type === 'memory') {
      await abortMemoryMediaUploadSession(batch.context.tenantName, batch.context.memoryId, file.uploadId)
    } else if (batch.context.type === 'public-share-root') {
      await abortPublicShareMediaUploadSession(batch.context.shareId, file.uploadId)
    } else {
      await abortPublicShareMemoryMediaUploadSession(batch.context.shareId, batch.context.memoryId, file.uploadId)
    }
  } catch {
    // Local cancellation should not be blocked by a missing or already-cleaned server session.
  }
}

function uploadQueueId(): string {
  return typeof crypto !== 'undefined' && 'randomUUID' in crypto
    ? crypto.randomUUID()
    : `upl_${Date.now()}_${Math.random().toString(16).slice(2)}`
}

function orderedBatches(): UploadQueueBatch[] {
  return orderUploadBatches(batches)
}

export function orderUploadBatches(batchesToOrder: UploadQueueBatch[]): UploadQueueBatch[] {
  return [...batchesToOrder].sort((left, right) => {
    const startedDiff = left.startedAt - right.startedAt
    return startedDiff || left.sequence - right.sequence
  })
}

function isRecoverableUploadInterruption(error: unknown): boolean {
  return error instanceof Error && isRecoverableUploadInterruptionMessage(error.message)
}

function isRecoverableUploadInterruptionMessage(message: string): boolean {
  return message === 'Request aborted' || message === 'Request failed'
}

function isActiveFileStatus(status: UploadFileStatus): boolean {
  return status === 'queued' || status === 'uploading'
}

function setFileStatus(file: UploadQueueFile, status: UploadFileStatus, error?: string) {
  file.status = status
  if (error !== undefined) file.error = error
  touchQueue()
}

function setFileProgress(file: UploadQueueFile, loadedBytes: number) {
  file.loadedBytes = loadedBytes
  touchQueue()
}

function setFileSession(file: UploadQueueFile, uploadId: string) {
  file.uploadId = uploadId
  touchQueue()
}

function setFileError(file: UploadQueueFile, error: string) {
  file.error = error
  touchQueue()
}

function setBatchFinished(batch: UploadQueueBatch, finishedAt: number | null) {
  batch.finishedAt = finishedAt
  touchQueue()
}

function touchQueue() {
  queueRevision.value += 1
}

function trackQueueRevision() {
  void queueRevision.value
}

function uploadQueueMessage(key: string): string {
  return i18n.global.t(key)
}

export const __uploadQueueTestHooks = {
  queueRevision
}
