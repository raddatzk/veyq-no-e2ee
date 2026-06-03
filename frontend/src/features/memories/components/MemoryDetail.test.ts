import { config, flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { Memory, MemoryHeader, MemoryJournal, MemoryMedia, MemoryMediaBucket, MemoryTrack } from '../api'
import MemoryDetail from './MemoryDetail.vue'

const apiMocks = vi.hoisted(() => ({
  deleteAllMemoryMedia: vi.fn(),
  deleteMemoryMedia: vi.fn(),
  favoriteMemory: vi.fn(),
  unfavoriteMemory: vi.fn()
}))

vi.mock('../api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../api')>()),
  deleteAllMemoryMedia: apiMocks.deleteAllMemoryMedia,
  deleteMemoryMedia: apiMocks.deleteMemoryMedia,
  favoriteMemory: apiMocks.favoriteMemory,
  unfavoriteMemory: apiMocks.unfavoriteMemory
}))

describe('MemoryDetail', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    config.global.directives = {
      tooltip: {}
    }
  })

  it('renders header date total media and chapters', () => {
    const wrapper = mount(MemoryDetail, {
      props: {
        tenantName: 'tenant',
        memory: memory(),
        header: header({
          directMediaCount: 12,
          descendantMediaCount: 22,
          childMemoryCount: 3
        }),
        childMemories: [],
        media: []
      },
      global: { stubs: detailStubs() }
    })

    expect(wrapper.text()).toContain('03.05.2026 - 05.05.2026')
    expect(wrapper.text()).toContain('34 Medien')
    expect(wrapper.text()).toContain('3 Kapitel')
    expect(wrapper.text()).not.toContain('davon')
    expect(wrapper.text()).not.toContain('Unter-Memories')
  })

  it('does not render scope metadata', () => {
    const wrapper = mount(MemoryDetail, {
      props: {
        tenantName: '',
        memory: memory(),
        header: header({
          scope: null,
          directMediaCount: 5,
          descendantMediaCount: 0
        }),
        childMemories: [],
        media: []
      },
      global: { stubs: detailStubs() }
    })

    expect(wrapper.text()).toContain('5 Medien')
    expect(wrapper.text()).not.toContain('Tenant')
  })

  it('hides chapter count when there are no chapters', () => {
    const wrapper = mount(MemoryDetail, {
      props: {
        tenantName: 'tenant',
        memory: memory(),
        header: header({
          directMediaCount: 5,
          descendantMediaCount: 0,
          childMemoryCount: 0
        }),
        childMemories: [],
        media: []
      },
      global: { stubs: detailStubs() }
    })

    expect(wrapper.text()).not.toContain('0 Kapitel')
  })

  it('does not use the first media item as an implicit cover', () => {
    const wrapper = mount(MemoryDetail, {
      props: {
        tenantName: 'tenant',
        memory: memory({ coverUrl: null, mediaCount: 1 }),
        header: header({ coverUrl: null }),
        childMemories: [],
        media: [media()]
      },
      global: { stubs: detailStubs() }
    })

    expect(wrapper.find('.memory-detail-cover img').exists()).toBe(false)
    expect(wrapper.find('.memory-detail-cover .pi-images').exists()).toBe(true)
  })

  it('toggles the current memory as a favorite', async () => {
    apiMocks.favoriteMemory.mockResolvedValue(memory({ favorite: true }))
    const wrapper = mount(MemoryDetail, {
      props: {
        tenantName: 'tenant',
        memory: memory({ favorite: false }),
        header: header({ favorite: false }),
        childMemories: [],
        media: []
      },
      global: { stubs: detailStubs() }
    })

    const favoriteButton = findButton(wrapper, 'Favorisieren')
    expect(favoriteButton.attributes('type')).toBe('button')

    await favoriteButton.trigger('click')
    await flushPromises()

    expect(apiMocks.favoriteMemory).toHaveBeenCalledWith('tenant', 'mem-1')
    expect(findButton(wrapper, 'Favorit entfernen').exists()).toBe(true)
    expect(wrapper.emitted('changed')).toBeUndefined()
  })

  it('removes the current memory from favorites', async () => {
    apiMocks.unfavoriteMemory.mockResolvedValue(memory({ favorite: false }))
    const wrapper = mount(MemoryDetail, {
      props: {
        tenantName: 'tenant',
        memory: memory({ favorite: true }),
        header: header({ favorite: true }),
        childMemories: [],
        media: []
      },
      global: { stubs: detailStubs() }
    })

    await findButton(wrapper, 'Favorit entfernen').trigger('click')
    await flushPromises()

    expect(apiMocks.unfavoriteMemory).toHaveBeenCalledWith('tenant', 'mem-1')
    expect(findButton(wrapper, 'Favorisieren').exists()).toBe(true)
    expect(wrapper.emitted('changed')).toBeUndefined()
  })

  it('hides favorite actions when favorites are disabled', () => {
    const wrapper = mount(MemoryDetail, {
      props: {
        tenantName: '',
        memory: memory({ favorite: false }),
        header: header({ favorite: false }),
        childMemories: [],
        media: [],
        capabilities: { canFavorite: false }
      },
      global: { stubs: detailStubs() }
    })

    expect(wrapper.findAll('button').some((button) => button.attributes('aria-label') === 'Favorisieren')).toBe(false)
    expect(wrapper.find('[data-testid="menu-item-favorite"]').exists()).toBe(false)
    expect(apiMocks.favoriteMemory).not.toHaveBeenCalled()
    expect(apiMocks.unfavoriteMemory).not.toHaveBeenCalled()
  })

  it('uses and emits controlled active tab state', async () => {
    const wrapper = mount(MemoryDetail, {
      props: {
        tenantName: 'tenant',
        memory: memory(),
        header: header(),
        childMemories: [],
        media: [],
        activeTab: 'map'
      },
      global: { stubs: detailStubs() }
    })

    expect(wrapper.find('[data-testid="memory-tabs"]').attributes('data-active')).toBe('map')

    await wrapper.find('[data-testid="select-journal-tab"]').trigger('click')

    expect(wrapper.emitted('update:activeTab')?.[0]).toEqual(['journal'])
  })


  it('renders an explicit cover image', () => {
    const wrapper = mount(MemoryDetail, {
      props: {
        tenantName: 'tenant',
        memory: memory(),
        header: header({ coverUrl: '/api/tenant/memories/mem-1/cover/content' }),
        childMemories: [],
        media: []
      },
      global: { stubs: detailStubs() }
    })

    expect(wrapper.find('.memory-detail-cover img').exists()).toBe(true)
    expect(wrapper.find('.memory-detail-cover .pi-images').exists()).toBe(false)
  })

  it('shows download choices for direct and subtree original archives', async () => {
    const wrapper = mount(MemoryDetail, {
      props: {
        tenantName: 'tenant',
        memory: memory(),
        header: header({
          directMediaCount: 1,
          descendantMediaCount: 2
        }),
        childMemories: [],
        media: [media()]
      },
      global: { stubs: detailStubs() }
    })

    await findButton(wrapper, 'Herunterladen').trigger('click')
    const links = wrapper.findAll('.memory-download-option')

    expect(links).toHaveLength(2)
    expect(links[0].attributes('href')).toBe('/api/tenant/memories/mem-1/download?includeChildren=false&locale=de')
    expect(links[0].text()).toContain('1 Originale')
    expect(links[1].attributes('href')).toBe('/api/tenant/memories/mem-1/download?includeChildren=true&locale=de')
    expect(links[1].text()).toContain('3 Originale')
  })

  it('uses public share download urls and can hide the download action', async () => {
    const wrapper = mount(MemoryDetail, {
      props: {
        tenantName: '',
        memory: memory(),
        header: header({ directMediaCount: 1 }),
        childMemories: [],
        media: [media()],
        downloadUrl: (memoryId, includeChildren) => `/share/${memoryId}/download?children=${includeChildren}`
      },
      global: { stubs: detailStubs() }
    })

    await findButton(wrapper, 'Herunterladen').trigger('click')
    expect(wrapper.find('.memory-download-option').attributes('href')).toBe('/share/mem-1/download?children=false')

    await wrapper.setProps({ capabilities: { canDownload: false } })
    expect(wrapper.findAll('button').some((button) => button.attributes('aria-label') === 'Herunterladen')).toBe(false)
  })

  it('opens uploader share requests from the header action', async () => {
    const wrapper = mount(MemoryDetail, {
      props: {
        tenantName: 'tenant',
        memory: memory(),
        header: header(),
        childMemories: [],
        media: []
      },
      global: { stubs: detailStubs() }
    })

    await findButton(wrapper, 'Upload anfragen').trigger('click')

    expect(wrapper.find('[data-testid="uploader-share-dialog"]').exists()).toBe(true)
  })

  it('shows newly uploaded media immediately and updates only its bucket count', async () => {
    const uploaded = media({ id: 'media-uploaded', title: 'Fresh upload', bucketId: 'bucket-source' })
    const wrapper = mount(MemoryDetail, {
      props: {
        tenantName: 'tenant',
        memory: memory(),
        header: header({ directMediaCount: 0, descendantMediaCount: 0 }),
        childMemories: [],
        media: [],
        mediaBuckets: [
          mediaBucket({ id: 'bucket-source', displayName: 'Source', mediaCount: 0 }),
          mediaBucket({ id: 'bucket-other', displayName: 'Other', mediaCount: 4 })
        ]
      },
      global: { stubs: detailStubs() }
    })

    wrapper.findComponent({ name: 'MemoryUploadDialog' }).vm.$emit('uploaded', uploaded)
    await flushPromises()

    const mediaGrid = wrapper.findComponent({ name: 'MediaGrid' })
    const mediaGridProps = mediaGrid.props('media') as MemoryMedia[]
    expect(mediaGridProps.map((item) => item.id)).toEqual(['media-uploaded'])
    expect(wrapper.findComponent({ name: 'MemoryAssetBuckets' }).props('media')).toEqual(mediaGridProps)
    expect(wrapper.findComponent({ name: 'MemoryCoverDialog' }).props('media')).toEqual(mediaGridProps)
    expect(wrapper.text()).toContain('1 Medien')
    expect(bucketCountPairs(wrapper)).toEqual([
      ['bucket-source', 1],
      ['bucket-other', 4]
    ])

    await wrapper.setProps({
      media: [uploaded],
      mediaBuckets: [
        mediaBucket({ id: 'bucket-source', displayName: 'Source', mediaCount: 1 }),
        mediaBucket({ id: 'bucket-other', displayName: 'Other', mediaCount: 4 })
      ]
    })

    const refreshedMedia = wrapper.findComponent({ name: 'MediaGrid' }).props('media') as MemoryMedia[]
    expect(refreshedMedia.map((item) => item.id)).toEqual(['media-uploaded'])
    expect(bucketCountPairs(wrapper)).toEqual([
      ['bucket-source', 1],
      ['bucket-other', 4]
    ])
  })

  it('shows media uploaded directly into a visible child bucket and updates that count', async () => {
    const uploaded = media({
      id: 'media-uploaded-child',
      memoryId: 'child-mem-1',
      bucketId: 'bucket-child',
      title: 'Fresh bucket upload'
    })
    const wrapper = mount(MemoryDetail, {
      props: {
        tenantName: 'tenant',
        memory: memory(),
        header: header({ directMediaCount: 0, descendantMediaCount: 0 }),
        childMemories: [memory({ id: 'child-mem-1', path: '/trip/day-1' })],
        media: [],
        mediaBuckets: [
          mediaBucket({
            id: 'bucket-child',
            memoryId: 'child-mem-1',
            displayName: 'Day 1 uploads',
            mediaCount: 2
          })
        ]
      },
      global: { stubs: detailStubs() }
    })

    wrapper.findComponent({ name: 'MemoryUploadDialog' }).vm.$emit('uploaded', uploaded)
    await flushPromises()

    const mediaGridProps = wrapper.findComponent({ name: 'MediaGrid' }).props('media') as MemoryMedia[]
    expect(mediaGridProps.map((item) => item.id)).toEqual(['media-uploaded-child'])
    expect(wrapper.findComponent({ name: 'MemoryAssetBuckets' }).props('media')).toEqual(mediaGridProps)
    expect(bucketCountPairs(wrapper)).toEqual([['bucket-child', 3]])
    expect(wrapper.text()).toContain('1 Medien')
  })

  it('starts a memory upload batch when media files are dropped on the gallery', async () => {
    const uploaded = media({ id: 'media-dropped', title: 'Dropped photo' })
    const uploadMedia = vi.fn().mockResolvedValue(uploaded)
    const wrapper = mount(MemoryDetail, {
      props: {
        tenantName: 'tenant',
        memory: memory(),
        header: header(),
        childMemories: [],
        media: [],
        uploadMedia
      },
      global: { stubs: detailStubs() }
    })
    const image = new File(['image'], 'dropped.jpg', { type: 'image/jpeg' })
    const text = new File(['note'], 'note.txt', { type: 'text/plain' })

    await wrapper.find('.memory-gallery-drop-target').trigger('drop', {
      dataTransfer: dataTransfer([image, text])
    })
    await flushPromises()

    expect(uploadMedia).toHaveBeenCalledWith(
      'mem-1',
      { file: image, title: 'dropped.jpg' },
      expect.objectContaining({ signal: expect.any(AbortSignal) })
    )
    expect(wrapper.findComponent({ name: 'MediaGrid' }).props('media')).toEqual([
      expect.objectContaining({ id: 'media-dropped' })
    ])
  })

  it('shows the gallery drop hint only while dragging media files', async () => {
    const wrapper = mount(MemoryDetail, {
      props: {
        tenantName: 'tenant',
        memory: memory(),
        header: header(),
        childMemories: [],
        media: []
      },
      global: { stubs: detailStubs() }
    })

    expect(wrapper.find('.memory-dropzone').exists()).toBe(false)

    await wrapper.find('.memory-gallery-drop-target').trigger('dragenter', {
      dataTransfer: dataTransfer([])
    })

    expect(wrapper.find('.memory-dropzone').exists()).toBe(true)

    await wrapper.find('.memory-gallery-drop-target').trigger('dragleave')

    expect(wrapper.find('.memory-dropzone').exists()).toBe(false)
  })

  it('starts a child memory upload batch when media files are dropped on a chapter', async () => {
    const uploadMedia = vi.fn().mockResolvedValue(media({ id: 'media-child', memoryId: 'child-1' }))
    const wrapper = mount(MemoryDetail, {
      props: {
        tenantName: 'tenant',
        memory: memory(),
        header: header(),
        childMemories: [memory({ id: 'child-1', path: '/trip/day-1', displayName: 'Day 1' })],
        media: [],
        uploadMedia
      },
      global: { stubs: detailStubs() }
    })
    const image = new File(['image'], 'chapter.jpg', { type: 'image/jpeg' })

    await wrapper.find('.memory-child-drop-target').trigger('drop', {
      dataTransfer: dataTransfer([image])
    })
    await flushPromises()

    expect(uploadMedia).toHaveBeenCalledWith(
      'child-1',
      { file: image, title: 'chapter.jpg' },
      expect.objectContaining({ signal: expect.any(AbortSignal) })
    )
  })

  it('shows child memory drop hints only for the active drag target', async () => {
    const wrapper = mount(MemoryDetail, {
      props: {
        tenantName: 'tenant',
        memory: memory(),
        header: header(),
        childMemories: [
          memory({ id: 'child-1', path: '/trip/day-1', displayName: 'Day 1' }),
          memory({ id: 'child-2', path: '/trip/day-2', displayName: 'Day 2' })
        ],
        media: []
      },
      global: { stubs: detailStubs() }
    })
    const dropTargets = wrapper.findAll('.memory-child-drop-target')

    expect(wrapper.findAll('.memory-child-drop-hint')).toHaveLength(0)

    await dropTargets[1].trigger('dragenter', {
      dataTransfer: dataTransfer([])
    })

    expect(wrapper.findAll('.memory-child-drop-hint')).toHaveLength(1)
    expect(dropTargets[0].find('.memory-child-drop-hint').exists()).toBe(false)
    expect(dropTargets[1].find('.memory-child-drop-hint').exists()).toBe(true)
  })

  it('deduplicates repeated media ids before rendering gallery and buckets', async () => {
    const duplicated = media({ id: 'media-duplicate', title: 'Same media', bucketId: 'bucket-source' })
    const wrapper = mount(MemoryDetail, {
      props: {
        tenantName: 'tenant',
        memory: memory(),
        header: header({ directMediaCount: 1 }),
        childMemories: [],
        media: [duplicated, { ...duplicated }]
      },
      global: { stubs: detailStubs() }
    })

    const mediaGridProps = wrapper.findComponent({ name: 'MediaGrid' }).props('media') as MemoryMedia[]
    expect(mediaGridProps.map((item) => item.id)).toEqual(['media-duplicate'])
    expect(wrapper.findComponent({ name: 'MemoryAssetBuckets' }).props('media')).toEqual(mediaGridProps)
  })

  it('updates bucket counts after moving media locally', async () => {
    const moveMedia = vi.fn().mockResolvedValue([
      media({ id: 'media-1', bucketId: 'bucket-target' })
    ])
    const wrapper = mount(MemoryDetail, {
      props: {
        tenantName: 'tenant',
        memory: memory(),
        header: header({ directMediaCount: 2 }),
        childMemories: [],
        media: [
          media({ id: 'media-1', bucketId: 'bucket-source' }),
          media({ id: 'media-2', bucketId: 'bucket-source' })
        ],
        mediaBuckets: [
          mediaBucket({ id: 'bucket-source', displayName: 'Source', mediaCount: 2 }),
          mediaBucket({ id: 'bucket-target', displayName: 'Target', mediaCount: 0, primary: false })
        ],
        moveMedia
      },
      global: { stubs: detailStubs() }
    })

    const buckets = wrapper.findComponent({ name: 'MemoryAssetBuckets' })
    await (buckets.props('moveMedia') as (memoryId: string, mediaIds: string[], targetBucketId: string) => Promise<MemoryMedia[]>)(
      'mem-1',
      ['media-1'],
      'bucket-target'
    )
    await flushPromises()

    const updatedBuckets = wrapper.findComponent({ name: 'MemoryAssetBuckets' }).props('buckets') as MemoryMediaBucket[]
    expect(updatedBuckets.map((bucket) => [bucket.id, bucket.mediaCount])).toEqual([
      ['bucket-source', 1],
      ['bucket-target', 1]
    ])
  })

  it('removes anonymous primary buckets immediately after deleting a bucket with unloaded media', async () => {
    const deleteMediaBucket = vi.fn().mockResolvedValue(undefined)
    const wrapper = mount(MemoryDetail, {
      props: {
        tenantName: 'tenant',
        memory: memory(),
        header: header({ directMediaCount: 1 }),
        childMemories: [],
        media: [],
        mediaBuckets: [
          mediaBucket({
            id: 'bucket-source',
            displayName: 'Source',
            mediaCount: 1,
            primary: true,
            uploader: { type: 'ANONYMOUS', displayName: 'Alex' }
          })
        ],
        deleteMediaBucket
      },
      global: { stubs: detailStubs() }
    })

    const buckets = wrapper.findComponent({ name: 'MemoryAssetBuckets' })
    await (buckets.props('deleteBucket') as (memoryId: string, bucketId: string) => Promise<void>)(
      'mem-1',
      'bucket-source'
    )
    await flushPromises()

    expect(deleteMediaBucket).toHaveBeenCalledWith('bucket-source', 'mem-1')
    expect(bucketCountPairs(wrapper)).toEqual([])
  })

  it('wires tenant media deletion and removes deleted media locally', async () => {
    const deleteMedia = vi.fn().mockResolvedValue(undefined)
    const first = media({ id: 'media-1', bucketId: 'bucket-source' })
    const second = media({ id: 'media-2', bucketId: 'bucket-source' })
    const wrapper = mount(MemoryDetail, {
      props: {
        tenantName: 'tenant',
        memory: memory(),
        header: header({ directMediaCount: 2 }),
        childMemories: [],
        media: [first, second],
        mediaBuckets: [mediaBucket({ id: 'bucket-source', displayName: 'Source', mediaCount: 2 })],
        deleteMedia
      },
      global: { stubs: detailStubs() }
    })

    const mediaGrid = wrapper.findComponent({ name: 'MediaGrid' })
    await (mediaGrid.props('deleteMedia') as (mediaId: string, memoryId: string) => Promise<void>)('media-1', 'mem-1')
    mediaGrid.vm.$emit('deleted', first)
    await flushPromises()

    expect(deleteMedia).toHaveBeenCalledWith('media-1', 'mem-1')
    const refreshedMedia = wrapper.findComponent({ name: 'MediaGrid' }).props('media') as MemoryMedia[]
    expect(refreshedMedia.map((item) => item.id)).toEqual(['media-2'])
    expect(bucketCountPairs(wrapper)).toEqual([['bucket-source', 1]])
    expect(wrapper.text()).toContain('1 Medien')
  })

  it('renders journal markdown and media embeds', () => {
    const wrapper = mount(MemoryDetail, {
      props: {
        tenantName: 'tenant',
        memory: memory(),
        header: header(),
        journal: journal({ markdown: '# Beach\n\n::media:media-1 Sunset' }),
        childMemories: [],
        media: [media()]
      },
      global: { stubs: detailStubs() }
    })

    expect(wrapper.text()).toContain('Journal')
    expect(wrapper.find('.memory-journal-rendered h1').text()).toBe('Beach')
    expect(wrapper.find('.memory-journal-media img').attributes('src')).toBe('/api/tenant/media/media-1/preview')
  })

  it('renders comma-separated media embeds as a carousel with unavailable placeholders', () => {
    const wrapper = mount(MemoryDetail, {
      props: {
        tenantName: 'tenant',
        memory: memory(),
        header: header(),
        journal: journal({ markdown: '::media:media-1,missing-media Trip set' }),
        childMemories: [],
        media: [media()]
      },
      global: { stubs: detailStubs() }
    })

    expect(wrapper.find('.memory-journal-carousel').exists()).toBe(true)
    expect(wrapper.find('.memory-journal-carousel img').exists()).toBe(true)
    expect(wrapper.find('.p-carousel').attributes('data-autoplay-interval')).toBeUndefined()
    expect(wrapper.find('.memory-journal-unavailable').text()).toContain('Trip set')
  })

  it('changes journal carousel pages with native navigators', async () => {
    const wrapper = mount(MemoryDetail, {
      props: {
        tenantName: 'tenant',
        memory: memory(),
        header: header(),
        journal: journal({ markdown: '::media:media-1,media-2 Trip set' }),
        childMemories: [],
        media: [media(), media({ id: 'media-2', title: 'Second upload' })]
      },
      global: { stubs: detailStubs() }
    })

    expect(wrapper.find('.p-carousel').attributes('data-page')).toBe('0')
    await wrapper.find('.p-carousel-next-button').trigger('click')
    expect(wrapper.find('.p-carousel').attributes('data-page')).toBe('1')
    await wrapper.find('.p-carousel-prev-button').trigger('click')
    expect(wrapper.find('.p-carousel').attributes('data-page')).toBe('0')
  })

  it('opens journal media in a lightbox and synchronizes carousel navigation', async () => {
    const wrapper = mount(MemoryDetail, {
      props: {
        tenantName: 'tenant',
        memory: memory(),
        header: header(),
        journal: journal({ markdown: '::media:media-1,media-2 Trip set' }),
        childMemories: [],
        media: [media(), media({ id: 'media-2', title: 'Second upload' })]
      },
      global: { stubs: detailStubs() }
    })

    await wrapper.find('.p-carousel-next-button').trigger('click')
    expect(wrapper.find('.p-carousel').attributes('data-page')).toBe('1')

    await wrapper.find('.memory-journal-media-lightbox-trigger').trigger('click')

    const lightbox = wrapper.findComponent({ name: 'MediaLightbox' })
    expect(lightbox.props('media')).toEqual(expect.objectContaining({ id: 'media-2' }))
    expect(lightbox.props('selectedIndex')).toBe(1)

    lightbox.vm.$emit('previous')
    await flushPromises()

    expect(wrapper.find('.p-carousel').attributes('data-page')).toBe('0')
    expect(wrapper.findComponent({ name: 'MediaLightbox' }).props('media')).toEqual(
      expect.objectContaining({ id: 'media-1' })
    )
  })

  it('renders chapter playlist and track journal embeds with double colon syntax', async () => {
    const wrapper = mount(MemoryDetail, {
      props: {
        tenantName: 'tenant',
        memory: memory(),
        header: header(),
        journal: journal({
          markdown: [
            '::chapter:chapter-1 Day 2',
            '::playlist:playlist-1 Roadtrip',
            '::track:track-1 Route'
          ].join('\n\n')
        }),
        childMemories: [memory({ id: 'chapter-1', displayName: 'Day 2', path: '/trip/day-2' })],
        media: [media({ gpsLatitude: 52.515, gpsLongitude: 13.4 })],
        playlists: [
          {
            id: 'playlist-1',
            memoryId: 'mem-1',
            title: 'Roadtrip Mix',
            featured: true,
            provider: 'SPOTIFY',
            url: 'https://open.spotify.com/playlist/playlist-1',
            externalId: 'playlist-1',
            createdAt: '2026-05-24T16:30:00Z',
            updatedAt: '2026-05-24T16:30:00Z'
          }
        ],
        tracks: [
          track(),
          track({
            id: 'track-2',
            title: 'Valley Route',
            distanceMeters: 800,
            ascentMeters: 40,
            descentMeters: 50,
            startedAt: '2026-05-03T10:00:00Z',
            endedAt: '2026-05-03T10:30:00Z'
          })
        ]
      },
      global: { stubs: detailStubs() }
    })

    expect(wrapper.find('.memory-journal-link').text()).toContain('Day 2')
    await wrapper.find('.memory-journal-link').trigger('click')
    expect(wrapper.emitted('openMemory')?.[0]).toEqual([
      expect.objectContaining({ id: 'chapter-1', path: '/trip/day-2' })
    ])
    expect(wrapper.findAll('.memory-journal-context')[0].text()).toContain('Roadtrip')
    expect(wrapper.findAll('.memory-journal-context')[0].text()).toContain('Spotify')
    const playlistLink = wrapper.find<HTMLAnchorElement>('a.memory-journal-playlist-link')
    expect(playlistLink.attributes('href')).toBe('https://open.spotify.com/playlist/playlist-1')
    expect(playlistLink.attributes('target')).toBe('_blank')
    expect(playlistLink.attributes('rel')).toBe('noreferrer')
    expect(playlistLink.attributes('aria-label')).toBe('Beim Anbieter öffnen')
    expect(wrapper.find('.memory-journal-track').text()).toContain('Route')
    expect(wrapper.find('.memory-journal-track').text()).toContain('Distanz')
    expect(wrapper.find('.memory-journal-track').text()).toContain('1.2 km')
    expect(wrapper.findComponent({ name: 'TrackMap' }).props('tracks')).toEqual([
      expect.objectContaining({ id: 'track-1' })
    ])
    expect(wrapper.findComponent({ name: 'TrackMap' }).props('media')).toEqual([
      expect.objectContaining({ id: 'media-1' })
    ])
  })

  it('emits changed after saving the journal', async () => {
    const saveJournal = vi.fn().mockResolvedValue(journal({ markdown: 'Updated' }))
    const wrapper = mount(MemoryDetail, {
      props: {
        tenantName: 'tenant',
        memory: memory(),
        header: header(),
        journal: journal(),
        childMemories: [],
        media: [],
        saveJournal
      },
      global: { stubs: detailStubs() }
    })

    await wrapper.find('.memory-journal-empty button').trigger('click')
    await wrapper.find('.memory-journal-field textarea').setValue('Updated')
    await wrapper.find('.memory-journal-editor-actions button').trigger('click')
    await flushPromises()

    expect(saveJournal).toHaveBeenCalledWith('mem-1', { markdown: 'Updated' })
    expect(wrapper.emitted('changed')?.[0]).toEqual([memory()])
  })

  it('starts a journal live session when editing', async () => {
    const createJournalLiveSession = vi.fn().mockResolvedValue(liveSession())
    const wrapper = mount(MemoryDetail, {
      props: {
        tenantName: 'tenant',
        memory: memory(),
        header: header(),
        journal: journal(),
        childMemories: [],
        media: [],
        createJournalLiveSession
      },
      global: { stubs: detailStubs() }
    })

    await wrapper.find('.memory-journal-empty button').trigger('click')
    await flushPromises()

    expect(createJournalLiveSession).toHaveBeenCalledWith('mem-1')
    expect(wrapper.find('.memory-journal-live-editor').exists()).toBe(true)
    expect(wrapper.find('.memory-journal-editor-actions').text()).not.toContain('Speichern')
  })

  it('falls back to textarea journal editing when live session creation fails', async () => {
    const createJournalLiveSession = vi.fn().mockRejectedValue(new Error('offline'))
    const wrapper = mount(MemoryDetail, {
      props: {
        tenantName: 'tenant',
        memory: memory(),
        header: header(),
        journal: journal(),
        childMemories: [],
        media: [],
        createJournalLiveSession
      },
      global: { stubs: detailStubs() }
    })

    await wrapper.find('.memory-journal-empty button').trigger('click')
    await flushPromises()

    expect(wrapper.find('.memory-journal-field textarea').exists()).toBe(true)
    expect(wrapper.text()).toContain('Live Sync ist nicht verfügbar')
  })

  it('falls back to textarea journal editing when live editor cannot connect', async () => {
    const createJournalLiveSession = vi.fn().mockResolvedValue(liveSession())
    const wrapper = mount(MemoryDetail, {
      props: {
        tenantName: 'tenant',
        memory: memory(),
        header: header(),
        journal: journal({ markdown: 'Draft before live' }),
        childMemories: [],
        media: [],
        createJournalLiveSession
      },
      global: { stubs: detailStubs() }
    })

    await wrapper.find('.memory-journal-actions button').trigger('click')
    await flushPromises()
    await wrapper.findComponent({ name: 'MemoryJournalLiveEditor' }).vm.$emit('fallback')
    await flushPromises()

    expect(wrapper.find('.memory-journal-live-editor').exists()).toBe(false)
    expect((wrapper.find('.memory-journal-field textarea').element as HTMLTextAreaElement).value).toBe('Draft before live')
    expect(wrapper.text()).toContain('Live Sync ist nicht verfügbar')
  })

  it('shows markdown and veyq embed help while editing the journal', async () => {
    const wrapper = mount(MemoryDetail, {
      props: {
        tenantName: 'tenant',
        memory: memory(),
        header: header(),
        journal: journal(),
        childMemories: [],
        media: []
      },
      global: { stubs: detailStubs() }
    })

    await wrapper.find('.memory-journal-empty button').trigger('click')

    expect(wrapper.find('.memory-journal-help').text()).toContain('Markdown-Hilfe')
    expect(wrapper.find('.memory-journal-help').text()).toContain('::media:mediaId')
    expect(wrapper.find('.memory-journal-help').text()).toContain('::media:media1,media2')
    expect(wrapper.find('.memory-journal-help').text()).toContain('Bearbeiten-Icon')
    expect(wrapper.find('.memory-journal-help').text()).toContain('::chapter:chapterId')
    expect(wrapper.find('.memory-journal-help').text()).toContain('::playlist:playlistId')
    expect(wrapper.find('.memory-journal-help').text()).toContain('::track:trackId')
  })

  it('opens the media picker from the editor button and inserts selected media markdown', async () => {
    const wrapper = mount(MemoryDetail, {
      props: {
        tenantName: 'tenant',
        memory: memory(),
        header: header(),
        journal: journal(),
        childMemories: [],
        media: [media(), media({ id: 'media-2', title: 'Second upload' })]
      },
      global: { stubs: detailStubs() }
    })

    await wrapper.find('.memory-journal-empty button').trigger('click')
    const textarea = wrapper.find('.memory-journal-field textarea')
    await textarea.setValue('Intro\n')
    ;(textarea.element as HTMLTextAreaElement).setSelectionRange(6, 6)
    await findButton(wrapper, 'Medium einfügen').trigger('click')
    await wrapper.findAll('.memory-journal-media-option')[0].trigger('click')
    await wrapper.findAll('.memory-journal-media-option')[1].trigger('click')
    await findButton(wrapper, 'Medium einfügen', 1).trigger('click')

    expect((textarea.element as HTMLTextAreaElement).value).toBe('Intro\n::media:media-1,media-2 Galerie')
  })

  it('opens embed pickers from editor buttons and inserts chapter playlist and track markdown', async () => {
    const wrapper = mount(MemoryDetail, {
      props: {
        tenantName: 'tenant',
        memory: memory(),
        header: header(),
        journal: journal(),
        childMemories: [memory({ id: 'chapter-1', displayName: 'Day 2', path: '/trip/day-2' })],
        media: [],
        playlists: [
          {
            id: 'playlist-1',
            memoryId: 'mem-1',
            title: 'Roadtrip Mix',
            featured: true,
            provider: 'SPOTIFY',
            url: 'https://open.spotify.com/playlist/playlist-1',
            externalId: 'playlist-1',
            createdAt: '2026-05-24T16:30:00Z',
            updatedAt: '2026-05-24T16:30:00Z'
          }
        ],
        tracks: [track(), track({ id: 'track-2', title: 'Valley Route' })]
      },
      global: { stubs: detailStubs() }
    })

    await wrapper.find('.memory-journal-empty button').trigger('click')
    const textarea = wrapper.find('.memory-journal-field textarea')

    await findButton(wrapper, 'Kapitel einfügen').trigger('click')
    await wrapper.find('.memory-journal-embed-option').trigger('click')
    await findButton(wrapper, 'Kapitel einfügen', 1).trigger('click')
    expect((textarea.element as HTMLTextAreaElement).value).toBe('::chapter:chapter-1 Day 2')

    ;(textarea.element as HTMLTextAreaElement).setSelectionRange(
      (textarea.element as HTMLTextAreaElement).value.length,
      (textarea.element as HTMLTextAreaElement).value.length
    )
    await findButton(wrapper, 'Playlist einfügen').trigger('click')
    await wrapper.find('.memory-journal-embed-option').trigger('click')
    await findButton(wrapper, 'Playlist einfügen', 1).trigger('click')
    expect((textarea.element as HTMLTextAreaElement).value).toBe(
      '::chapter:chapter-1 Day 2::playlist:playlist-1 Roadtrip Mix'
    )

    ;(textarea.element as HTMLTextAreaElement).setSelectionRange(
      (textarea.element as HTMLTextAreaElement).value.length,
      (textarea.element as HTMLTextAreaElement).value.length
    )
    await findButton(wrapper, 'Track einfügen').trigger('click')
    await wrapper.findAll('.memory-journal-embed-option')[0].trigger('click')
    await wrapper.findAll('.memory-journal-embed-option')[1].trigger('click')
    await findButton(wrapper, 'Track einfügen', 1).trigger('click')
    expect((textarea.element as HTMLTextAreaElement).value).toBe(
      '::chapter:chapter-1 Day 2::playlist:playlist-1 Roadtrip Mix::track:track-1,track-2 2 Tracks'
    )
  })

  it('opens the media picker when typing media target and replaces the trigger', async () => {
    const wrapper = mount(MemoryDetail, {
      props: {
        tenantName: 'tenant',
        memory: memory(),
        header: header(),
        journal: journal(),
        childMemories: [],
        media: [media()]
      },
      global: { stubs: detailStubs() }
    })

    await wrapper.find('.memory-journal-empty button').trigger('click')
    const textarea = wrapper.find('.memory-journal-field textarea')
    await textarea.setValue('::media:')
    expect(wrapper.find('.memory-journal-media-picker').exists()).toBe(true)

    await wrapper.find('.memory-journal-media-option').trigger('click')
    await findButton(wrapper, 'Medium einfügen', 1).trigger('click')

    expect((textarea.element as HTMLTextAreaElement).value).toBe('::media:media-1 First upload')
  })

  it('opens the type picker when typing double colon and then inserts media', async () => {
    const wrapper = mount(MemoryDetail, {
      props: {
        tenantName: 'tenant',
        memory: memory(),
        header: header(),
        journal: journal(),
        childMemories: [],
        media: [media()]
      },
      global: { stubs: detailStubs() }
    })

    await wrapper.find('.memory-journal-empty button').trigger('click')
    const textarea = wrapper.find('.memory-journal-field textarea')
    await textarea.setValue('')
    await textarea.setValue('::')
    expect(wrapper.find('.memory-journal-type-picker').exists()).toBe(true)

    await wrapper.findAll('.memory-journal-type-option')[0].trigger('click')
    expect(wrapper.find('.memory-journal-media-picker').exists()).toBe(true)

    await wrapper.find('.memory-journal-media-option').trigger('click')
    await findButton(wrapper, 'Medium einfügen', 1).trigger('click')

    expect((textarea.element as HTMLTextAreaElement).value).toBe('::media:media-1 First upload')
  })

  it('navigates and dismisses the type picker with keyboard and outside clicks', async () => {
    const wrapper = mount(MemoryDetail, {
      props: {
        tenantName: 'tenant',
        memory: memory(),
        header: header(),
        journal: journal(),
        childMemories: [memory({ id: 'chapter-1', displayName: 'Day 2', path: '/trip/day-2' })],
        media: [media()]
      },
      attachTo: document.body,
      global: { stubs: detailStubs() }
    })

    await wrapper.find('.memory-journal-empty button').trigger('click')
    const textarea = wrapper.find('.memory-journal-field textarea')
    await textarea.setValue('::')
    await flushPromises()

    const picker = wrapper.find('.memory-journal-type-picker')
    expect(picker.exists()).toBe(true)
    expect(document.activeElement).toBe(picker.element)
    expect(wrapper.findAll('.memory-journal-type-option')[0].classes()).toContain('memory-journal-type-option-active')

    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true }))
    await flushPromises()
    expect(document.activeElement).toBe(picker.element)
    expect(wrapper.findAll('.memory-journal-type-option')[1].classes()).toContain('memory-journal-type-option-active')

    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
    await flushPromises()
    expect(wrapper.find('.memory-journal-type-picker').exists()).toBe(false)

    await textarea.setValue('')
    await textarea.setValue('::')
    await flushPromises()
    expect(wrapper.find('.memory-journal-type-picker').exists()).toBe(true)
    document.body.dispatchEvent(new Event('pointerdown', { bubbles: true }))
    await flushPromises()

    expect(wrapper.find('.memory-journal-type-picker').exists()).toBe(false)
    wrapper.unmount()
  })

  it('preserves an existing media caption when replacing a media target', async () => {
    const wrapper = mount(MemoryDetail, {
      props: {
        tenantName: 'tenant',
        memory: memory(),
        header: header(),
        journal: journal(),
        childMemories: [],
        media: [media()]
      },
      global: { stubs: detailStubs() }
    })

    await wrapper.find('.memory-journal-empty button').trigger('click')
    const textarea = wrapper.find('.memory-journal-field textarea')
    await textarea.setValue('::media:')
    await wrapper.find('.memory-journal-media-option').trigger('click')
    await findButton(wrapper, 'Medium einfügen', 1).trigger('click')

    expect((textarea.element as HTMLTextAreaElement).value).toBe('::media:media-1 First upload')
  })

  it('opens the media picker from an existing media tag double-click and replaces that tag', async () => {
    const wrapper = mount(MemoryDetail, {
      props: {
        tenantName: 'tenant',
        memory: memory(),
        header: header(),
        journal: journal(),
        childMemories: [],
        media: [media(), media({ id: 'media-2', title: 'Second upload' })]
      },
      global: { stubs: detailStubs() }
    })

    await wrapper.find('.memory-journal-empty button').trigger('click')
    const textarea = wrapper.find('.memory-journal-field textarea')
    await textarea.setValue('Intro\n::media:media-1 Cover')
    ;(textarea.element as HTMLTextAreaElement).setSelectionRange(15, 15)
    await textarea.trigger('dblclick')

    expect(wrapper.find('.memory-journal-media-picker').exists()).toBe(true)
    expect((wrapper.find('.memory-journal-media-picker input').element as HTMLInputElement).value).toBe('Cover')
    expect(wrapper.findAll('.memory-journal-media-option-selected')).toHaveLength(1)

    await wrapper.findAll('.memory-journal-media-option')[1].trigger('click')
    await findButton(wrapper, 'Medium einfügen', 1).trigger('click')

    expect((textarea.element as HTMLTextAreaElement).value).toBe('Intro\n::media:media-1,media-2 Cover')
  })

  it('deletes an existing media pill from the edit dialog', async () => {
    const wrapper = mount(MemoryDetail, {
      props: {
        tenantName: 'tenant',
        memory: memory(),
        header: header(),
        journal: journal(),
        childMemories: [],
        media: [media()]
      },
      global: { stubs: detailStubs() }
    })

    await wrapper.find('.memory-journal-empty button').trigger('click')
    const textarea = wrapper.find('.memory-journal-field textarea')
    await textarea.setValue('Intro\n::media:media-1 Cover\nOutro')
    ;(textarea.element as HTMLTextAreaElement).setSelectionRange(15, 15)
    await textarea.trigger('dblclick')

    expect(wrapper.find('.memory-journal-media-picker').exists()).toBe(true)
    await wrapper.find('.memory-journal-dialog-delete').trigger('click')

    expect((textarea.element as HTMLTextAreaElement).value).toBe('Intro\n\nOutro')
    expect(wrapper.find('.memory-journal-media-picker').exists()).toBe(false)
  })

  it('deletes an existing chapter pill from the edit dialog', async () => {
    const wrapper = mount(MemoryDetail, {
      props: {
        tenantName: 'tenant',
        memory: memory(),
        header: header(),
        journal: journal(),
        childMemories: [memory({ id: 'chapter-1', displayName: 'Day 2', path: '/trip/day-2' })],
        media: []
      },
      global: { stubs: detailStubs() }
    })

    await wrapper.find('.memory-journal-empty button').trigger('click')
    const textarea = wrapper.find('.memory-journal-field textarea')
    await textarea.setValue('Intro\n::chapter:chapter-1 Day 2\nOutro')
    ;(textarea.element as HTMLTextAreaElement).setSelectionRange(16, 16)
    await textarea.trigger('dblclick')

    expect(wrapper.find('.memory-journal-embed-picker').exists()).toBe(true)
    await wrapper.find('.memory-journal-dialog-delete').trigger('click')

    expect((textarea.element as HTMLTextAreaElement).value).toBe('Intro\n\nOutro')
    expect(wrapper.find('.memory-journal-embed-picker').exists()).toBe(false)
  })

  it('opens the media picker from the editor button when the cursor is inside an existing media tag', async () => {
    const wrapper = mount(MemoryDetail, {
      props: {
        tenantName: 'tenant',
        memory: memory(),
        header: header(),
        journal: journal(),
        childMemories: [],
        media: [media(), media({ id: 'media-2', title: 'Second upload' })]
      },
      global: { stubs: detailStubs() }
    })

    await wrapper.find('.memory-journal-empty button').trigger('click')
    const textarea = wrapper.find('.memory-journal-field textarea')
    await textarea.setValue('Intro\n::media:media-1 Cover')
    ;(textarea.element as HTMLTextAreaElement).setSelectionRange(15, 15)
    await findButton(wrapper, 'Medium einfügen').trigger('click')

    expect(wrapper.find('.memory-journal-media-picker').exists()).toBe(true)
    expect((wrapper.find('.memory-journal-media-picker input').element as HTMLInputElement).value).toBe('Cover')
    expect(wrapper.findAll('.memory-journal-media-option-selected')).toHaveLength(1)

    await wrapper.findAll('.memory-journal-media-option')[1].trigger('click')
    await findButton(wrapper, 'Medium einfügen', 1).trigger('click')

    expect((textarea.element as HTMLTextAreaElement).value).toBe('Intro\n::media:media-1,media-2 Cover')
  })

  it('opens the media picker from a live editor autocomplete trigger and replaces the trigger', async () => {
    const createJournalLiveSession = vi.fn().mockResolvedValue(liveSession())
    const wrapper = mount(MemoryDetail, {
      props: {
        tenantName: 'tenant',
        memory: memory(),
        header: header(),
        journal: journal({ markdown: 'Intro ::media:' }),
        childMemories: [],
        media: [media()],
        createJournalLiveSession
      },
      global: { stubs: detailStubs() }
    })

    await wrapper.find('.memory-journal-actions button').trigger('click')
    await flushPromises()
    wrapper.findComponent({ name: 'MemoryJournalLiveEditor' }).vm.$emit('media-trigger', { start: 6, end: 17 })
    await flushPromises()

    expect(wrapper.find('.memory-journal-media-picker').exists()).toBe(true)

    await wrapper.find('.memory-journal-media-option').trigger('click')
    await findButton(wrapper, 'Medium einfügen', 1).trigger('click')

    expect(wrapper.findComponent({ name: 'MemoryJournalLiveEditor' }).props('modelValue')).toBe(
      'Intro ::media:media-1 First upload'
    )
  })

  it('opens the media picker from an existing carousel tag with media order preserved', async () => {
    const wrapper = mount(MemoryDetail, {
      props: {
        tenantName: 'tenant',
        memory: memory(),
        header: header(),
        journal: journal(),
        childMemories: [],
        media: [media(), media({ id: 'media-2', title: 'Second upload' })]
      },
      global: { stubs: detailStubs() }
    })

    await wrapper.find('.memory-journal-empty button').trigger('click')
    const textarea = wrapper.find('.memory-journal-field textarea')
    await textarea.setValue('::media:media-2,media-1 Set')
    ;(textarea.element as HTMLTextAreaElement).setSelectionRange(10, 10)
    await textarea.trigger('dblclick')

    expect(wrapper.findAll('.memory-journal-media-option-selected')).toHaveLength(2)
    await findButton(wrapper, 'Medium einfügen', 1).trigger('click')

    expect((textarea.element as HTMLTextAreaElement).value).toBe('::media:media-2,media-1 Set')
  })

  it('does not open the media picker when double-clicking outside a media tag', async () => {
    const wrapper = mount(MemoryDetail, {
      props: {
        tenantName: 'tenant',
        memory: memory(),
        header: header(),
        journal: journal(),
        childMemories: [],
        media: [media()]
      },
      global: { stubs: detailStubs() }
    })

    await wrapper.find('.memory-journal-empty button').trigger('click')
    const textarea = wrapper.find('.memory-journal-field textarea')
    await textarea.setValue('Plain journal text')
    ;(textarea.element as HTMLTextAreaElement).setSelectionRange(5, 5)
    await textarea.trigger('dblclick')

    expect(wrapper.find('.memory-journal-media-picker').exists()).toBe(false)
  })
})

function detailStubs() {
  return {
    Button: {
      props: ['label', 'icon', 'disabled'],
      emits: ['click'],
      template: '<button v-bind="$attrs" :disabled="disabled" @click="$emit(\'click\', $event)"><span v-if="icon" :class="icon" />{{ label }}</button>'
    },
    Menu: {
      props: ['model'],
      template: `
        <div>
          <span
            v-for="item in model"
            :key="item.label"
            :data-testid="item.icon === 'pi pi-star' || item.icon === 'pi pi-star-fill' ? 'menu-item-favorite' : undefined"
          >
            {{ item.label }}
          </span>
        </div>
      `
    },
    Carousel: {
      props: ['value', 'autoplayInterval', 'page', 'showNavigators'],
      emits: ['update:page'],
      template: `
        <div class="p-carousel" :data-autoplay-interval="autoplayInterval" :data-page="page">
          <div class="p-carousel-content">
            <button
              v-if="showNavigators"
              type="button"
              class="p-carousel-prev-button"
              @click="$emit('update:page', ((page || 0) + value.length - 1) % value.length)"
            />
            <div v-for="item in value" :key="item.id"><slot name="item" :data="item" /></div>
            <button
              v-if="showNavigators"
              type="button"
              class="p-carousel-next-button"
              @click="$emit('update:page', ((page || 0) + 1) % value.length)"
            />
          </div>
        </div>
      `
    },
    Dialog: {
      props: ['visible'],
      emits: ['update:visible', 'hide'],
      template: '<div v-if="visible"><slot /><slot name="footer" /></div>'
    },
    InputText: {
      props: ['modelValue', 'placeholder'],
      emits: ['update:modelValue', 'input'],
      template: '<input :value="modelValue" :placeholder="placeholder" @input="$emit(\'update:modelValue\', $event.target.value); $emit(\'input\', $event)" />'
    },
    Message: { template: '<div><slot /></div>' },
    Tabs: {
      props: ['value'],
      emits: ['update:value'],
      template: `
        <section data-testid="memory-tabs" :data-active="value">
          <button type="button" data-testid="select-journal-tab" @click="$emit('update:value', 'journal')" />
          <slot />
        </section>
      `
    },
    TabList: { template: '<div><slot /></div>' },
    Tab: { template: '<button><slot /></button>' },
    TabPanels: { template: '<div><slot /></div>' },
    TabPanel: { template: '<div><slot /></div>' },
    MediaGrid: {
      name: 'MediaGrid',
      props: ['media', 'deleteMedia'],
      template: '<div />'
    },
    MemoryAssetBuckets: {
      name: 'MemoryAssetBuckets',
      props: [
        'media',
        'buckets',
        'canUpload',
        'canEditMetadata',
        'canDeleteMedia',
        'updateMetadataBatch',
        'createBucket',
        'updateBucket',
        'deleteBucket',
        'moveMedia',
        'deleteMediaBatch'
      ],
      template: '<div />'
    },
    MemoryContextPanel: {
      name: 'MemoryContextPanel',
      props: ['media', 'tracks'],
      template: '<div />'
    },
    TrackMap: {
      name: 'TrackMap',
      props: ['track', 'tracks', 'media'],
      template: '<div class="track-map" />'
    },
    MediaLightbox: {
      name: 'MediaLightbox',
      props: ['media', 'mediaItems', 'selectedIndex'],
      emits: ['close', 'previous', 'next'],
      template: '<div class="media-lightbox" />'
    },
    MemoryCard: { template: '<article />' },
    MemoryJournalLiveEditor: {
      name: 'MemoryJournalLiveEditor',
      props: ['modelValue', 'session'],
      emits: ['update:modelValue', 'status', 'fallback', 'media-trigger', 'media-tag-edit', 'embed-trigger', 'embed-tag-edit'],
      methods: {
        insertMarkdown(
          this: { $emit: (event: string, value: string) => void; modelValue: string },
          markdown: string,
          range?: { start: number; end: number }
        ) {
          const nextValue = range
            ? `${this.modelValue.slice(0, range.start)}${markdown}${this.modelValue.slice(range.end)}`
            : `${this.modelValue}${markdown}`
          this.$emit('update:modelValue', nextValue)
        },
        currentMediaTagRange() {
          return null
        },
        currentJournalEmbedRange() {
          return null
        }
      },
      template: '<div class="memory-journal-live-editor"><textarea :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" /></div>'
    },
    MemoryCoverImage: { template: '<img :alt="alt" />', props: ['alt'] },
    DeleteMemoryDialog: { template: '<div />' },
    EditMemoryDialog: { template: '<div />' },
    MemoryCoverDialog: { name: 'MemoryCoverDialog', props: ['media'], template: '<div />' },
    MemoryShareDialog: { template: '<div />' },
    MemoryUploaderShareDialog: {
      props: ['visible'],
      template: '<div v-if="visible" data-testid="uploader-share-dialog" />'
    },
    MemoryUploadDialog: { name: 'MemoryUploadDialog', template: '<div />' }
  }
}

function findButton(wrapper: ReturnType<typeof mount>, label: string, occurrence = 0) {
  const button = wrapper
    .findAll('button')
    .filter((candidate) => candidate.text().includes(label) || candidate.attributes('aria-label') === label)[occurrence]
  if (!button) throw new Error(`Button not found: ${label}`)
  return button
}

function bucketCountPairs(wrapper: ReturnType<typeof mount>) {
  const buckets = wrapper.findComponent({ name: 'MemoryAssetBuckets' }).props('buckets') as MemoryMediaBucket[]
  return buckets.map((bucket) => [bucket.id, bucket.mediaCount])
}

function journal(overrides: Partial<MemoryJournal> = {}): MemoryJournal {
  return {
    memoryId: 'mem-1',
    markdown: '',
    updatedAt: '2026-05-24T10:00:00Z',
    ...overrides
  }
}

function liveSession() {
  return {
    memoryId: 'mem-1',
    documentId: 'tenant/tenant/memory/mem-1/journal',
    websocketUrl: '/api/collaboration/journal',
    token: 'token',
    expiresAt: '2026-05-24T10:15:00Z',
    displayName: 'Alex',
    color: '#5b6ee1',
    canEdit: true
  }
}

function memory(overrides: Partial<Memory> = {}): Memory {
  return {
    id: 'mem-1',
    parentId: null,
    name: 'trip',
    displayName: 'Trip',
    path: '/trip',
    coverMediaId: null,
    coverUrl: null,
    coverCrop: null,
    startDate: '2026-05-03',
    endDate: '2026-05-05',
    childCount: 0,
    mediaCount: 0,
    favorite: false,
    scope: { type: 'TENANT' },
    ...overrides
  }
}

function header(overrides: Partial<MemoryHeader> = {}): MemoryHeader {
  return {
    id: 'mem-1',
    displayName: 'Trip',
    path: '/trip',
    coverUrl: null,
    coverCrop: null,
    startDate: '2026-05-03',
    endDate: '2026-05-05',
    directMediaCount: 0,
    descendantMediaCount: 0,
    childMemoryCount: 0,
    favorite: false,
    scope: { type: 'TENANT' },
    participants: [],
    ...overrides
  }
}

function media(overrides: Partial<MemoryMedia> = {}): MemoryMedia {
  return {
    id: 'media-1',
    memoryId: 'mem-1',
    title: 'First upload',
    uploader: { type: 'USER', displayName: 'Kevin' },
    createdAt: '2026-05-24T16:30:00Z',
    updatedAt: '2026-05-24T16:30:00Z',
    capturedAtOverride: false,
    mimeType: 'image/jpeg',
    sizeBytes: 12,
    contentUrl: '/api/tenant/media/media-1/content',
    thumbnailUrl: '/api/tenant/media/media-1/thumbnail',
    previewUrl: '/api/tenant/media/media-1/preview',
    posterUrl: null,
    capturedAt: null,
    cameraMake: null,
    cameraModel: null,
    lensModel: null,
    imageWidth: null,
    imageHeight: null,
    gpsLatitude: null,
    gpsLongitude: null,
    highlight: false,
    ...overrides
  }
}

function mediaBucket(overrides: Partial<MemoryMediaBucket> = {}): MemoryMediaBucket {
  return {
    id: 'bucket-source',
    memoryId: 'mem-1',
    displayName: 'Source',
    primary: true,
    uploader: { type: 'USER', displayName: 'Kevin', userId: 1 },
    canManage: true,
    mediaCount: 0,
    createdAt: '2026-05-24T16:30:00Z',
    updatedAt: '2026-05-24T16:30:00Z',
    ...overrides
  }
}

function track(overrides: Partial<MemoryTrack> = {}): MemoryTrack {
  return {
    id: 'track-1',
    memoryId: 'mem-1',
    title: 'Summit Route',
    activityType: 'HIKING',
    originalFilename: 'summit.gpx',
    distanceMeters: 1200,
    ascentMeters: 80,
    descentMeters: 30,
    pointCount: 3,
    startedAt: '2026-05-03T08:00:00Z',
    endedAt: '2026-05-03T09:00:00Z',
    bounds: { minLatitude: 0, minLongitude: 0, maxLatitude: 1, maxLongitude: 1 },
    geometry: {
      type: 'LineString',
      coordinates: [
        [0, 0],
        [1, 1],
        [2, 2]
      ]
    },
    contentUrl: '/api/tenant/memories/mem-1/tracks/track-1/content',
    createdAt: '2026-05-24T16:30:00Z',
    updatedAt: '2026-05-24T16:30:00Z',
    ...overrides
  }
}

function dataTransfer(files: File[]) {
  return {
    files,
    types: ['Files'],
    dropEffect: 'none'
  }
}
