import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { UploadCancelledError } from '../../../shared/api/httpClient'
import type { UploadMediaOptions } from '../api'
import { clearUploadQueue, startUploadBatch } from '../composables/useUploadQueue'
import UploadStatusMenu from './UploadStatusMenu.vue'

describe('UploadStatusMenu', () => {
  afterEach(() => {
    clearUploadQueue()
    vi.useRealTimers()
  })

  it('renders remaining time for active uploads', async () => {
    vi.useFakeTimers()
    vi.setSystemTime(0)
    let options: UploadMediaOptions | undefined
    const batch = startUploadBatch({
      label: 'Batch',
      files: [new File(['x'.repeat(100)], 'a.jpg')],
      context: { type: 'memory', tenantName: 'tenant', memoryId: 'mem-1' },
      uploadFile: (_file, uploadOptions) =>
        new Promise<void>((_resolve, reject) => {
          options = uploadOptions
          uploadOptions.onProgress?.({ loaded: 0, total: 100 })
          uploadOptions.signal?.addEventListener('abort', () => reject(new UploadCancelledError()))
        })
    })
    await flushPromises()
    vi.advanceTimersByTime(1000)
    options?.onProgress?.({ loaded: 50, total: 100 })

    const wrapper = mount(UploadStatusMenu, {
      props: {
        inline: true,
        batches: [batch]
      },
      global: {
        stubs: {
          Button: { template: '<button><slot />{{ label }}</button>', props: ['label'] }
        }
      }
    })

    expect(wrapper.text()).toContain('50% · ca. weniger als 1 Min. verbleibend')
  })
})
