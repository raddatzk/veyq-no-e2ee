import { afterEach, describe, expect, it, vi } from 'vitest'
import { nextTick } from 'vue'
import { UploadCancelledError } from '../../../shared/api/httpClient'
import {
  batchRemainingSeconds,
  batchProgress,
  cancelAll,
  cancelFile,
  clearUploadQueue,
  fileRemainingSeconds,
  orderUploadBatches,
  startUploadBatch,
  useUploadQueue
} from './useUploadQueue'
import type { UploadMediaOptions } from '../api'

describe('useUploadQueue', () => {
  afterEach(() => {
    clearUploadQueue()
    vi.useRealTimers()
  })

  async function flushUploadQueue() {
    await nextTick()
    await Promise.resolve()
    await Promise.resolve()
    await Promise.resolve()
    await Promise.resolve()
    await Promise.resolve()
  }

  it('uploads files sequentially and tracks status progress', async () => {
    const resolvers: Array<() => void> = []
    const uploadFile = vi.fn(
      (_file: File, options: UploadMediaOptions) =>
        new Promise<void>((resolve) => {
          options.onProgress?.({ loaded: 2, total: 5 })
          resolvers.push(resolve)
        })
    )

    const batch = startUploadBatch({
      label: 'Batch',
      files: [new File(['first'], 'first.jpg'), new File(['second'], 'second.jpg')],
      context: { type: 'memory', tenantName: 'tenant', memoryId: 'mem-1' },
      uploadFile
    })

    await flushUploadQueue()
    expect(batch.files[0].status).toBe('uploading')
    expect(batch.files[1].status).toBe('queued')
    expect(batchProgress(batch)).toBeGreaterThan(0)

    resolvers[0]()
    await flushUploadQueue()

    expect(batch.files[0].status).toBe('done')
    expect(batch.files[1].status).toBe('uploading')
    expect(uploadFile).toHaveBeenCalledTimes(2)

    resolvers[1]()
    await flushUploadQueue()
  })

  it('runs multiple started batches through the same sequential runner', async () => {
    const resolvers: Array<() => void> = []
    const uploadedNames: string[] = []
    const uploadFile = vi.fn(
      (file: File) =>
        new Promise<void>((resolve) => {
          uploadedNames.push(file.name)
          resolvers.push(resolve)
        })
    )

    const first = startUploadBatch({
      label: 'First',
      files: [new File(['a'], 'a.jpg')],
      context: { type: 'memory', tenantName: 'tenant', memoryId: 'mem-1' },
      uploadFile
    })
    const second = startUploadBatch({
      label: 'Second',
      files: [new File(['b'], 'b.jpg')],
      context: { type: 'memory', tenantName: 'tenant', memoryId: 'mem-1' },
      uploadFile
    })

    await flushUploadQueue()
    expect(first.files[0].status).toBe('uploading')
    expect(second.files[0].status).toBe('queued')
    expect(uploadedNames).toEqual(['a.jpg'])

    resolvers[0]()
    await flushUploadQueue()

    expect(first.files[0].status).toBe('done')
    expect(second.files[0].status).toBe('uploading')
    expect(uploadedNames).toEqual(['a.jpg', 'b.jpg'])

    resolvers[1]()
    await flushUploadQueue()
  })

  it('orders batches by start time for queue display', () => {
    const first = startUploadBatch({
      label: 'First',
      files: [new File(['a'], 'a.jpg')],
      context: { type: 'memory', tenantName: 'tenant', memoryId: 'mem-1' },
      uploadFile: () => Promise.resolve()
    })
    const second = startUploadBatch({
      label: 'Second',
      files: [new File(['b'], 'b.jpg')],
      context: { type: 'memory', tenantName: 'tenant', memoryId: 'mem-1' },
      uploadFile: () => Promise.resolve()
    })

    expect(orderUploadBatches([second, first]).map((batch) => batch.id)).toEqual([first.id, second.id])
  })

  it('calls onFileComplete for successful uploads only', async () => {
    const onFileComplete = vi.fn()
    const results = [{ id: 'media-1' }, { id: 'media-2' }]
    const uploadFile = vi.fn((file: File) => {
      if (file.name === 'second.jpg') return Promise.reject(new Error('Nope'))
      return Promise.resolve(results[0])
    })
    const batch = startUploadBatch({
      label: 'Batch',
      files: [new File(['first'], 'first.jpg'), new File(['second'], 'second.jpg')],
      context: { type: 'memory', tenantName: 'tenant', memoryId: 'mem-1' },
      uploadFile,
      onFileComplete
    })

    await flushUploadQueue()

    expect(onFileComplete).toHaveBeenCalledTimes(1)
    expect(onFileComplete).toHaveBeenCalledWith(batch.files[0], results[0])
    expect(batch.files[1].status).toBe('failed')
  })

  it('cancels individual queued and active files', async () => {
    const uploadFile = vi.fn(
      (_file: File, options: UploadMediaOptions) =>
        new Promise<void>((_resolve, reject) => {
          options.signal?.addEventListener('abort', () => reject(new UploadCancelledError()))
        })
    )

    const batch = startUploadBatch({
      label: 'Batch',
      files: [new File(['first'], 'first.jpg'), new File(['second'], 'second.jpg')],
      context: { type: 'memory', tenantName: 'tenant', memoryId: 'mem-1' },
      uploadFile
    })

    await flushUploadQueue()
    cancelFile(batch.files[1])
    cancelFile(batch.files[0])
    await flushUploadQueue()

    expect(batch.files[0].status).toBe('cancelled')
    expect(batch.files[1].status).toBe('cancelled')
  })

  it('cancels all active and queued uploads', async () => {
    const uploadFile = vi.fn(
      (_file: File, options: UploadMediaOptions) =>
        new Promise<void>((_resolve, reject) => {
          options.signal?.addEventListener('abort', () => reject(new UploadCancelledError()))
        })
    )
    const batch = startUploadBatch({
      label: 'Batch',
      files: [new File(['a'], 'a.jpg'), new File(['b'], 'b.jpg')],
      context: { type: 'memory', tenantName: 'tenant', memoryId: 'mem-1' },
      uploadFile
    })

    await flushUploadQueue()
    cancelAll()
    await flushUploadQueue()

    expect(batch.files.map((file) => file.status)).toEqual(['cancelled', 'cancelled'])
  })

  it('keeps interrupted browser uploads queued while the tab is alive', async () => {
    const uploadFile = vi.fn(() => Promise.reject(new Error('Request aborted')))
    const batch = startUploadBatch({
      label: 'Batch',
      files: [new File(['a'], 'a.jpg'), new File(['b'], 'b.jpg')],
      context: { type: 'memory', tenantName: 'tenant', memoryId: 'mem-1' },
      uploadFile
    })

    await flushUploadQueue()

    expect(batch.finishedAt).toBeNull()
    expect(batch.files.map((file) => file.status)).toEqual(['queued', 'queued'])
  })

  it('marks failed uploads and cancels the remaining queued files in the batch', async () => {
    const uploadFile = vi.fn(() => Promise.reject(new Error('Nope')))
    const batch = startUploadBatch({
      label: 'Batch',
      files: [new File(['a'], 'a.jpg'), new File(['b'], 'b.jpg')],
      context: { type: 'memory', tenantName: 'tenant', memoryId: 'mem-1' },
      uploadFile
    })

    await flushUploadQueue()

    expect(batch.files[0].status).toBe('failed')
    expect(batch.files[0].error).toBe('Nope')
    expect(batch.files[1].status).toBe('cancelled')
  })

  it('exposes visible active queue counts', async () => {
    const { activeFileCount, hasVisibleUploads } = useUploadQueue()
    let resolveUpload: (() => void) | undefined
    startUploadBatch({
      label: 'Batch',
      files: [new File(['a'], 'a.jpg')],
      context: { type: 'memory', tenantName: 'tenant', memoryId: 'mem-1' },
      uploadFile: () =>
        new Promise<void>((resolve) => {
          resolveUpload = resolve
        })
    })
    await flushUploadQueue()

    expect(activeFileCount.value).toBe(1)
    expect(hasVisibleUploads.value).toBe(true)

    resolveUpload?.()
    await flushUploadQueue()

    expect(activeFileCount.value).toBe(0)
  })

  it('estimates remaining upload time from measured progress', async () => {
    vi.useFakeTimers()
    vi.setSystemTime(0)
    let options: UploadMediaOptions | undefined
    let resolveUpload: (() => void) | undefined
    const batch = startUploadBatch({
      label: 'Batch',
      files: [new File(['x'.repeat(100)], 'a.jpg')],
      context: { type: 'memory', tenantName: 'tenant', memoryId: 'mem-1' },
      uploadFile: (_file, uploadOptions) =>
        new Promise<void>((resolve) => {
          options = uploadOptions
          resolveUpload = resolve
          uploadOptions.onProgress?.({ loaded: 0, total: 100 })
        })
    })

    await flushUploadQueue()
    expect(fileRemainingSeconds(batch.files[0])).toBeNull()

    vi.advanceTimersByTime(1000)
    options?.onProgress?.({ loaded: 50, total: 100 })

    expect(fileRemainingSeconds(batch.files[0])).toBe(1)

    resolveUpload?.()
    await flushUploadQueue()
    expect(fileRemainingSeconds(batch.files[0])).toBeNull()
  })

  it('uses active upload speed for batch remaining time including queued files', async () => {
    vi.useFakeTimers()
    vi.setSystemTime(0)
    let options: UploadMediaOptions | undefined
    const batch = startUploadBatch({
      label: 'Batch',
      files: [new File(['x'.repeat(100)], 'a.jpg'), new File(['x'.repeat(100)], 'b.jpg')],
      context: { type: 'memory', tenantName: 'tenant', memoryId: 'mem-1' },
      uploadFile: (_file, uploadOptions) =>
        new Promise<void>((_resolve, reject) => {
          options = uploadOptions
          uploadOptions.onProgress?.({ loaded: 0, total: 100 })
          uploadOptions.signal?.addEventListener('abort', () => reject(new UploadCancelledError()))
        })
    })

    await flushUploadQueue()
    vi.advanceTimersByTime(1000)
    options?.onProgress?.({ loaded: 50, total: 100 })

    expect(batchRemainingSeconds(batch)).toBe(3)
  })

  it('does not count resumed bytes as newly transferred speed', async () => {
    vi.useFakeTimers()
    vi.setSystemTime(0)
    let options: UploadMediaOptions | undefined
    const batch = startUploadBatch({
      label: 'Batch',
      files: [new File(['x'.repeat(160)], 'a.jpg')],
      context: { type: 'memory', tenantName: 'tenant', memoryId: 'mem-1' },
      uploadFile: (_file, uploadOptions) =>
        new Promise<void>((_resolve, reject) => {
          options = uploadOptions
          uploadOptions.onSession?.({
            uploadId: 'upl-1',
            memoryId: 'mem-1',
            filename: 'a.jpg',
            mimeType: 'image/jpeg',
            sizeBytes: 160,
            chunkSizeBytes: 10,
            uploadedChunks: [0, 1, 2, 3, 4, 5, 6, 7, 8, 9],
            uploadedBytes: 100,
            createdAt: new Date().toISOString(),
            updatedAt: new Date().toISOString()
          })
          uploadOptions.signal?.addEventListener('abort', () => reject(new UploadCancelledError()))
        })
    })

    await flushUploadQueue()
    vi.advanceTimersByTime(1000)
    options?.onProgress?.({ loaded: 110, total: 160 })

    expect(fileRemainingSeconds(batch.files[0])).toBe(5)
  })
})
