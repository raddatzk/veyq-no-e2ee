import { config, flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { Memory, MemoryHeader, MemoryJournal, MemoryMedia } from '../api'
import MemoryDetail from './MemoryDetail.vue'

const apiMocks = vi.hoisted(() => ({
  favoriteMemory: vi.fn(),
  unfavoriteMemory: vi.fn()
}))

vi.mock('../api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../api')>()),
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

    await findButton(wrapper, 'Favorisieren').trigger('click')
    await flushPromises()

    expect(apiMocks.favoriteMemory).toHaveBeenCalledWith('tenant', 'mem-1')
    expect(wrapper.emitted('changed')?.[0]).toEqual([expect.objectContaining({ favorite: true })])
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
    expect(wrapper.emitted('changed')?.[0]).toEqual([expect.objectContaining({ favorite: false })])
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
        activeTab: 'tracks'
      },
      global: { stubs: detailStubs() }
    })

    expect(wrapper.find('[data-testid="memory-tabs"]').attributes('data-active')).toBe('tracks')

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

  it('shows newly uploaded media immediately without duplicating after prop refresh', async () => {
    const uploaded = media({ id: 'media-uploaded', title: 'Fresh upload' })
    const wrapper = mount(MemoryDetail, {
      props: {
        tenantName: 'tenant',
        memory: memory(),
        header: header({ directMediaCount: 0, descendantMediaCount: 0 }),
        childMemories: [],
        media: []
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

    await wrapper.setProps({ media: [uploaded] })

    const refreshedMedia = wrapper.findComponent({ name: 'MediaGrid' }).props('media') as MemoryMedia[]
    expect(refreshedMedia.map((item) => item.id)).toEqual(['media-uploaded'])
  })

  it('renders journal markdown and media embeds', () => {
    const wrapper = mount(MemoryDetail, {
      props: {
        tenantName: 'tenant',
        memory: memory(),
        header: header(),
        journal: journal({ markdown: '# Beach\n\n![Sunset](veyq:media/media-1)' }),
        childMemories: [],
        media: [media()]
      },
      global: { stubs: detailStubs() }
    })

    expect(wrapper.text()).toContain('Journal')
    expect(wrapper.find('.memory-journal-rendered h1').text()).toBe('Beach')
    expect(wrapper.find('.memory-journal-media img').exists()).toBe(true)
  })

  it('renders comma-separated media embeds as a carousel with unavailable placeholders', () => {
    const wrapper = mount(MemoryDetail, {
      props: {
        tenantName: 'tenant',
        memory: memory(),
        header: header(),
        journal: journal({ markdown: '![Trip set](veyq:media/media-1,missing-media)' }),
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
        journal: journal({ markdown: '![Trip set](veyq:media/media-1,media-2)' }),
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
    expect(wrapper.find('.memory-journal-help').text()).toContain('veyq:media/mediaId')
    expect(wrapper.find('.memory-journal-help').text()).toContain('veyq:media/media1,media2')
    expect(wrapper.find('.memory-journal-help').text()).toContain('Doppelklick auf ein Media-Tag')
    expect(wrapper.find('.memory-journal-help').text()).toContain('veyq:map?lat=52.5&lng=13.4&zoom=12')
    expect(wrapper.find('.memory-journal-help').text()).toContain('veyq:track/{trackId}')
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

    expect((textarea.element as HTMLTextAreaElement).value).toBe('Intro\n![Galerie](veyq:media/media-1,media-2)')
  })

  it('opens the media picker when typing veyq media target and replaces the trigger', async () => {
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
    await textarea.setValue('veyq:media/')
    expect(wrapper.find('.memory-journal-media-picker').exists()).toBe(true)

    await wrapper.find('.memory-journal-media-option').trigger('click')
    await findButton(wrapper, 'Medium einfügen', 1).trigger('click')

    expect((textarea.element as HTMLTextAreaElement).value).toBe('![First upload](veyq:media/media-1)')
  })

  it('replaces an incomplete markdown image media target without nesting markdown', async () => {
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
    await textarea.setValue('![](veyq:media/')
    expect(wrapper.find('.memory-journal-media-picker').exists()).toBe(true)

    await wrapper.find('.memory-journal-media-option').trigger('click')
    await findButton(wrapper, 'Medium einfügen', 1).trigger('click')

    expect((textarea.element as HTMLTextAreaElement).value).toBe('![First upload](veyq:media/media-1)')
  })

  it('preserves an existing markdown image caption when replacing a media target', async () => {
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
    await textarea.setValue('![Cover](veyq:media/')
    await wrapper.find('.memory-journal-media-option').trigger('click')
    await findButton(wrapper, 'Medium einfügen', 1).trigger('click')

    expect((textarea.element as HTMLTextAreaElement).value).toBe('![Cover](veyq:media/media-1)')
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
    await textarea.setValue('Intro ![Cover](veyq:media/media-1) outro')
    ;(textarea.element as HTMLTextAreaElement).setSelectionRange(18, 18)
    await textarea.trigger('dblclick')

    expect(wrapper.find('.memory-journal-media-picker').exists()).toBe(true)
    expect((wrapper.find('.memory-journal-media-picker input').element as HTMLInputElement).value).toBe('Cover')
    expect(wrapper.findAll('.memory-journal-media-option-selected')).toHaveLength(1)

    await wrapper.findAll('.memory-journal-media-option')[1].trigger('click')
    await findButton(wrapper, 'Medium einfügen', 1).trigger('click')

    expect((textarea.element as HTMLTextAreaElement).value).toBe('Intro ![Cover](veyq:media/media-1,media-2) outro')
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
    await textarea.setValue('![Set](veyq:media/media-2,media-1)')
    ;(textarea.element as HTMLTextAreaElement).setSelectionRange(10, 10)
    await textarea.trigger('dblclick')

    expect(wrapper.findAll('.memory-journal-media-option-selected')).toHaveLength(2)
    await findButton(wrapper, 'Medium einfügen', 1).trigger('click')

    expect((textarea.element as HTMLTextAreaElement).value).toBe('![Set](veyq:media/media-2,media-1)')
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
      template: '<button type="button" v-bind="$attrs" :disabled="disabled" @click="$emit(\'click\', $event)"><span v-if="icon" :class="icon" />{{ label }}</button>'
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
    MediaGrid: { name: 'MediaGrid', props: ['media'], template: '<div />' },
    MemoryAssetBuckets: { name: 'MemoryAssetBuckets', props: ['media'], template: '<div />' },
    MemoryCard: { template: '<article />' },
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

function journal(overrides: Partial<MemoryJournal> = {}): MemoryJournal {
  return {
    memoryId: 'mem-1',
    markdown: '',
    updatedAt: '2026-05-24T10:00:00Z',
    ...overrides
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
    capturedAtOverride: false,
    mimeType: 'image/jpeg',
    sizeBytes: 12,
    contentUrl: '/api/tenant/media/media-1/content',
    previewUrl: '/api/tenant/media/media-1/preview',
    capturedAt: null,
    cameraMake: null,
    cameraModel: null,
    lensModel: null,
    imageWidth: null,
    imageHeight: null,
    gpsLatitude: null,
    gpsLongitude: null,
    ...overrides
  }
}
