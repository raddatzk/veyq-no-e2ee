import { afterEach, describe, expect, it, vi } from 'vitest'
import { nextTick } from 'vue'
import { UploadCancelledError } from '../../../shared/api/httpClient'
import {
  batchProgress,
  cancelAll,
  cancelFile,
  clearUploadQueue,
  startUploadBatch,
  useUploadQueue
} from './useUploadQueue'
import type { UploadMediaOptions } from '../api'

describe('useUploadQueue', () => {
  afterEach(() => {
    clearUploadQueue()
  })

  async function flushUploadQueue() {
    await nextTick()
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
})
