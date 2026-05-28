import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { authState } from '../../../authState'
import type { MemoryMedia } from '../api'
import MemoryMediaMap from './MemoryMediaMap.vue'

const leafletMock = vi.hoisted(() => ({
  eventHandlers: new Map<string, () => void>(),
  layerGroups: [] as Array<{ layers: unknown[]; clearLayers: () => void; getLayers: () => unknown[] }>,
  map: vi.fn(),
  markerInstances: [] as Array<{
    options: { icon?: { html?: HTMLElement } }
    trigger: (event: string) => void
  }>
}))

vi.mock('leaflet', () => {
  class Marker {
    handlers = new Map<string, () => void>()
    constructor(
      public latLng: [number, number],
      public options: { icon?: { html?: HTMLElement } }
    ) {
      leafletMock.markerInstances.push(this)
    }
    on = vi.fn((event: string, handler: () => void) => {
      this.handlers.set(event, handler)
      return this
    })
    addTo = vi.fn((layer: { layers?: unknown[] }) => {
      layer.layers?.push(this)
      return this
    })
    getLatLng = vi.fn(() => ({ lat: 52.1, lng: 13.1 }))
    trigger(event: string) {
      this.handlers.get(event)?.()
    }
  }
  return {
    default: {
      map: vi.fn((element: HTMLElement, options: object) => {
        leafletMock.map(element, options)
        return {
          on: vi.fn((events: string, handler: () => void) => {
            events.split(' ').forEach((event) => leafletMock.eventHandlers.set(event, handler))
          }),
          off: vi.fn((events: string) => {
            events.split(' ').forEach((event) => leafletMock.eventHandlers.delete(event))
          }),
          invalidateSize: vi.fn(),
          fitBounds: vi.fn(),
          latLngToLayerPoint: vi.fn(([lat, lng]: [number, number]) => {
            const point = {
              x: Math.round((lng - 13.1) * 100_000),
              y: Math.round((lat - 52.1) * 100_000),
              distanceTo(other: { x: number; y: number }) {
                return Math.hypot(point.x - other.x, point.y - other.y)
              }
            }
            return point
          }),
          remove: vi.fn(),
          setView: vi.fn()
        }
      }),
      layerGroup: vi.fn(() => {
        const layerGroup = {
          layers: [] as unknown[],
          addTo: vi.fn(() => layerGroup),
          clearLayers: vi.fn(() => {
            layerGroup.layers = []
          }),
          getLayers: vi.fn(() => layerGroup.layers)
        }
        leafletMock.layerGroups.push(layerGroup)
        return layerGroup
      }),
      marker: vi.fn((latLng: [number, number], options: { icon?: { html?: HTMLElement } }) =>
        new Marker(latLng, options)
      ),
      tileLayer: vi.fn(() => ({ addTo: vi.fn(() => ({ remove: vi.fn() })) })),
      latLngBounds: vi.fn(() => ({})),
      divIcon: vi.fn((options: { html?: HTMLElement }) => options),
      Marker
    }
  }
})

describe('MemoryMediaMap', () => {
  afterEach(() => {
    window.localStorage.clear()
    authState.isAuthenticated = false
    authState.user = null
    authState.userLabel = ''
    authState.tenants = []
    leafletMock.eventHandlers.clear()
    leafletMock.layerGroups = []
    leafletMock.map.mockClear()
    leafletMock.markerInstances = []
  })

  it('enables direct scroll wheel touch and control zooming', async () => {
    window.localStorage.setItem('veyq:osm-tiles-enabled', 'true')

    mountMap()
    await flushPromises()

    expect(leafletMock.map).toHaveBeenCalledWith(
      expect.any(HTMLElement),
      expect.objectContaining({
        scrollWheelZoom: true,
        touchZoom: true,
        zoomControl: true
      })
    )
  })

  it('keeps OpenStreetMap tile consent anonymous without a signed-in user', async () => {
    const wrapper = mountMap()

    await buttonByText(wrapper, 'OpenStreetMap laden').trigger('click')

    expect(window.localStorage.getItem('veyq:osm-tiles-enabled')).toBe('true')
    expect(window.localStorage.getItem('veyq:user:7:osm-tiles-enabled')).toBeNull()
  })

  it('keeps OpenStreetMap tile consent scoped to the signed-in user', async () => {
    authState.isAuthenticated = true
    authState.user = {
      userId: 7,
      email: 'owner@example.test',
      displayName: 'Owner',
      permalink: 'owner',
      serverRole: 'ServerOwner',
      passkeyCount: 0
    }
    const wrapper = mountMap()

    await buttonByText(wrapper, 'OpenStreetMap laden').trigger('click')

    expect(window.localStorage.getItem('veyq:user:7:osm-tiles-enabled')).toBe('true')
    expect(window.localStorage.getItem('veyq:osm-tiles-enabled')).toBeNull()
  })

  it('does not render a map-local OpenStreetMap disable button after consent', () => {
    window.localStorage.setItem('veyq:osm-tiles-enabled', 'true')

    const wrapper = mountMap()

    expect(wrapper.text()).not.toContain('OpenStreetMap deaktivieren')
  })

  it('clusters overlapping marker previews with a count badge', async () => {
    window.localStorage.setItem('veyq:osm-tiles-enabled', 'true')

    mountMap([media({ id: 'media-1', longitude: 13.1 }), media({ id: 'media-2', longitude: 13.1002 })])
    await flushPromises()

    const markers = currentMarkers()
    expect(markers).toHaveLength(1)
    expect(markers[0].options.icon?.html?.textContent).toContain('2')
    expect(
      markers[0].options.icon?.html?.classList.contains('memory-media-map-marker-cluster')
    ).toBe(true)
  })

  it('keeps lightbox navigation inside the clicked marker cluster', async () => {
    window.localStorage.setItem('veyq:osm-tiles-enabled', 'true')
    const wrapper = mountMap([
      media({ id: 'media-1', longitude: 13.1 }),
      media({ id: 'media-2', longitude: 13.1002 }),
      media({ id: 'media-3', longitude: 13.2 })
    ])
    await flushPromises()

    currentMarkers()[0].trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-testid="lightbox"]').text()).toContain('media-1')

    await wrapper.get('[data-testid="lightbox-next"]').trigger('click')
    expect(wrapper.get('[data-testid="lightbox"]').text()).toContain('media-2')
    expect(wrapper.find('[data-testid="lightbox-next"]').exists()).toBe(false)
  })

  it('supports arrow key navigation inside the clicked marker cluster', async () => {
    window.localStorage.setItem('veyq:osm-tiles-enabled', 'true')
    const wrapper = mountMap([
      media({ id: 'media-1', longitude: 13.1 }),
      media({ id: 'media-2', longitude: 13.1002 }),
      media({ id: 'media-3', longitude: 13.2 })
    ])
    await flushPromises()

    currentMarkers()[0].trigger('click')
    await flushPromises()

    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight' }))
    await flushPromises()

    expect(wrapper.get('[data-testid="lightbox"]').text()).toContain('media-2')
    expect(wrapper.find('[data-testid="lightbox-next"]').exists()).toBe(false)
  })

  it('keeps distant marker previews separate', async () => {
    window.localStorage.setItem('veyq:osm-tiles-enabled', 'true')

    mountMap([media({ id: 'media-1', longitude: 13.1 }), media({ id: 'media-2', longitude: 13.2 })])
    await flushPromises()

    const markers = currentMarkers()
    expect(markers).toHaveLength(2)
    expect(markers[0].options.icon?.html?.textContent).not.toContain('2')
  })
})

function mountMap(mediaItems = [media()]) {
  return mount(MemoryMediaMap, {
    props: {
      tenantName: 'friends',
      media: mediaItems
    },
    global: {
      stubs: {
        MediaLightbox: {
          props: ['media', 'canShowNext', 'canShowPrevious'],
          emits: ['next', 'previous', 'close'],
          template: `
            <div data-testid="lightbox">
              <span>{{ media.id }}</span>
              <button v-if="canShowPrevious" data-testid="lightbox-previous" @click="$emit('previous')">previous</button>
              <button v-if="canShowNext" data-testid="lightbox-next" @click="$emit('next')">next</button>
              <button data-testid="lightbox-close" @click="$emit('close')">close</button>
            </div>
          `
        }
      }
    }
  })
}

function currentMarkers() {
  const layerGroup = leafletMock.layerGroups.at(-1)
  if (!layerGroup) throw new Error('No marker layer was created')
  return layerGroup.layers as Array<{
    options: { icon?: { html?: HTMLElement } }
    trigger: (event: string) => void
  }>
}

function media(options: { id?: string; longitude?: number; latitude?: number } = {}): MemoryMedia {
  return {
    id: options.id || 'media-1',
    memoryId: 'memory-1',
    title: 'A place',
    mimeType: 'image/jpeg',
    uploader: {
      type: 'USER',
      displayName: 'Owner',
      permalink: 'owner',
      userId: 7
    },
    capturedAtOverride: false,
    sizeBytes: 1024,
    capturedAt: null,
    gpsLatitude: options.latitude ?? 52.1,
    gpsLongitude: options.longitude ?? 13.1,
    cameraMake: null,
    cameraModel: null,
    lensModel: null,
    contentUrl: '/api/friends/memories/memory-1/media/media-1/content',
    thumbnailUrl: '/api/friends/memories/memory-1/media/media-1/thumbnail',
    previewUrl: '/api/friends/memories/memory-1/media/media-1/preview',
    imageWidth: 1200,
    imageHeight: 800,
    createdAt: '2026-05-24T10:00:00Z'
  }
}

function buttonByText(wrapper: VueWrapper, text: string) {
  const button = wrapper.findAll('button').find((candidate) => candidate.text().includes(text))
  if (!button) throw new Error(`Button not found: ${text}`)
  return button
}
