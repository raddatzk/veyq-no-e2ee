import { mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { updateMediaMetadata } from '../../features/explorer/api'
import ExplorerMediaLightbox from './ExplorerMediaLightbox.vue'
import type { ExplorerMedia } from '../../features/explorer/api'

vi.mock('../../features/explorer/api', () => ({
  updateMediaMetadata: vi.fn()
}))

const updateMediaMetadataMock = vi.mocked(updateMediaMetadata)

function media(overrides: Partial<ExplorerMedia> = {}): ExplorerMedia {
  return {
    id: 'media-1',
    title: 'Beach',
    albumPath: '/photos',
    ownerUserId: 1,
    uploaderDisplayName: 'Kevin',
    createdAt: '2026-01-01T10:00:00Z',
    capturedAt: '2025-12-31T18:30:00Z',
    capturedAtOverride: false,
    mimeType: 'image/jpeg',
    sizeBytes: 2048,
    contentUrl: '/api/friends/media/media-1/content',
    cameraMake: 'Canon',
    cameraModel: 'R6',
    lensModel: 'RF 35mm',
    imageWidth: 6000,
    imageHeight: 4000,
    gpsLatitude: 52.52,
    gpsLongitude: 13.405,
    ...overrides
  }
}

describe('ExplorerMediaLightbox', () => {
  beforeEach(() => {
    vi.resetAllMocks()
  })

  it('renders available metadata rows', () => {
    const wrapper = mount(ExplorerMediaLightbox, {
      props: {
        tenantName: 'friends',
        media: media(),
        canShowPrevious: false,
        canShowNext: false
      }
    })

    expect(wrapper.text()).toContain('Aufnahmezeit')
    expect(wrapper.text()).toContain('Uploadzeit')
    expect(wrapper.text()).toContain('Kevin')
    expect(wrapper.text()).toContain('Canon R6')
    expect(wrapper.text()).toContain('RF 35mm')
    expect(wrapper.text()).toContain('6000 x 4000 px')
    expect(wrapper.text()).toContain('52.520000, 13.405000')
  })

  it('saves corrected captured date and emits updated media', async () => {
    const updated = media({ capturedAt: '2026-01-02T12:00:00Z', capturedAtOverride: true })
    updateMediaMetadataMock.mockResolvedValue(updated)
    const wrapper = mount(ExplorerMediaLightbox, {
      props: {
        tenantName: 'friends',
        media: media(),
        canShowPrevious: false,
        canShowNext: false
      }
    })

    await wrapper.find('.lightbox-text-button').trigger('click')
    await wrapper.find('input[type="datetime-local"]').setValue('2026-01-02T12:00')
    await wrapper.find('form').trigger('submit.prevent')

    expect(updateMediaMetadataMock).toHaveBeenCalledWith('friends', 'media-1', {
      capturedAt: new Date('2026-01-02T12:00').toISOString()
    })
    expect(wrapper.emitted('updated')?.[0]).toEqual([updated])
  })

  it('toggles mobile details sheet state', async () => {
    const wrapper = mount(ExplorerMediaLightbox, {
      props: {
        tenantName: 'friends',
        media: media(),
        canShowPrevious: false,
        canShowNext: false
      }
    })

    expect(wrapper.find('.media-lightbox-details-open').exists()).toBe(false)
    await wrapper.find('.media-lightbox-sheet-handle').trigger('click')
    expect(wrapper.find('.media-lightbox-details-open').exists()).toBe(true)
  })
})
