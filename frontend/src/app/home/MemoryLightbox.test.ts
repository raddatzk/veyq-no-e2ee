import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import MemoryLightbox from './MemoryLightbox.vue'
import type { GalleryTile } from '../../features/gallery/gallery'

const tile: GalleryTile = {
  id: 'media-1',
  url: 'blob:thumb',
  aspectRatio: 1,
  mimeType: 'image/jpeg',
  takenAt: '2026-01-01T00:00:00Z',
  highlighted: false,
  preview: { blobId: 'p', totalChunks: 1, byteSize: 1 },
  original: { blobId: 'o', totalChunks: 1, byteSize: 1 },
  createdBy: 'user:u2',
}

describe('MemoryLightbox', () => {
  it('shows a linked uploader and emits the uploader clickthrough', async () => {
    const w = mount(MemoryLightbox, {
      props: {
        imageUrl: 'blob:preview',
        tile,
        tileCount: 1,
        downloading: false,
        canMoveTile: false,
        moveBuckets: [],
        uploaderName: 'Mira',
        uploaderUserId: 'u2',
      },
    })
    expect(w.find('.lightbox-uploader').text()).toContain('Mira')
    await w.find('.lightbox-uploader').trigger('click')
    expect(w.emitted('openUploader')).toHaveLength(1)
  })
})
