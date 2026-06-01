import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { authState } from '../../../authState'
import type { MemoryMedia, MemoryTrack } from '../api'
import MemoryContextPanel from './MemoryContextPanel.vue'

const leafletMock = vi.hoisted(() => ({
  layerGroups: [] as Array<{ layers: unknown[]; clearLayers: () => void; getLayers: () => unknown[] }>,
  map: vi.fn(),
  mapInstances: [] as Array<{ fitBounds: ReturnType<typeof vi.fn>; setView: ReturnType<typeof vi.fn> }>,
  markerInstances: [] as Array<{
    options: { icon?: { html?: HTMLElement }; title?: string }
    trigger: (event: string) => void
  }>,
  polylineInstances: [] as Array<{
    options: { color?: string; dashArray?: string; weight?: number }
    trigger: (event: string) => void
  }>
}))

vi.mock('leaflet', () => {
  class Marker {
    handlers = new Map<string, () => void>()
    constructor(
      public latLng: [number, number],
      public options: { icon?: { html?: HTMLElement }; title?: string }
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
    trigger(event: string) {
      this.handlers.get(event)?.()
    }
  }

  class Polyline {
    handlers = new Map<string, () => void>()
    constructor(
      public latLngs: [number, number][],
      public options: { color?: string; dashArray?: string; weight?: number }
    ) {
      leafletMock.polylineInstances.push(this)
    }
    on = vi.fn((event: string, handler: () => void) => {
      this.handlers.set(event, handler)
      return this
    })
    addTo = vi.fn((layer: { layers?: unknown[] }) => {
      layer.layers?.push(this)
      return this
    })
    trigger(event: string) {
      this.handlers.get(event)?.()
    }
  }

  return {
    default: {
      map: vi.fn((element: HTMLElement, options: object) => {
        leafletMock.map(element, options)
        const mapInstance = {
          on: vi.fn(),
          off: vi.fn(),
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
        leafletMock.mapInstances.push(mapInstance)
        return mapInstance
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
      marker: vi.fn((latLng: [number, number], options: { icon?: { html?: HTMLElement }; title?: string }) =>
        new Marker(latLng, options)
      ),
      polyline: vi.fn((latLngs: [number, number][], options: { color?: string; dashArray?: string; weight?: number }) =>
        new Polyline(latLngs, options)
      ),
      tileLayer: vi.fn(() => ({ addTo: vi.fn(() => ({ remove: vi.fn() })) })),
      latLngBounds: vi.fn(() => ({})),
      divIcon: vi.fn((options: { html?: HTMLElement }) => options),
      Marker,
      Polyline
    }
  }
})

describe('MemoryContextPanel', () => {
  afterEach(() => {
    window.localStorage.clear()
    authState.isAuthenticated = false
    authState.user = null
    authState.userLabel = ''
    authState.tenants = []
    leafletMock.layerGroups = []
    leafletMock.map.mockClear()
    leafletMock.mapInstances = []
    leafletMock.markerInstances = []
    leafletMock.polylineInstances = []
  })

  it('shows located media and track metrics in one context surface', async () => {
    const wrapper = mountPanel({
      media: [media()],
      tracks: [
        track({
          distanceMeters: 2400,
          ascentMeters: 180,
          descentMeters: 95,
          startedAt: '2026-05-03T08:00:00Z',
          endedAt: '2026-05-03T09:35:00Z'
        })
      ]
    })
    await flushPromises()

    expect(wrapper.text()).toContain('1 Medien mit Standort · 1 Tracks')
    expect(wrapper.text()).toContain('Morning Walk')
    expect(wrapper.text()).toContain('2.4 km')
    expect(wrapper.text()).toContain('1 h 35 min')
    expect(wrapper.text()).toContain('180 m')
    expect(wrapper.text()).toContain('95 m')
    const endpointClassNames = leafletMock.markerInstances
      .map((marker) => marker.options.icon?.html?.querySelector('.pi')?.className)
      .filter(Boolean)
    expect(endpointClassNames).toEqual(expect.arrayContaining(['pi pi-circle-fill', 'pi pi-flag']))
  })

  it('uses deterministic distinct track colors and activity line styles', async () => {
    mountPanel({
      tracks: [
        track({ id: 'track-1', activityType: 'HIKING' }),
        track({ id: 'track-2', activityType: 'RUNNING' })
      ]
    })
    await flushPromises()

    const polylines = leafletMock.polylineInstances.slice(-2)
    expect(polylines).toHaveLength(2)
    expect(polylines[0].options.color).not.toBe(polylines[1].options.color)
    expect(polylines[0].options.dashArray).toBeUndefined()
    expect(polylines[1].options.dashArray).toBe('2 8')
  })

  it('fits the map to a visible track when the track row is clicked', async () => {
    const wrapper = mountPanel({
      media: [media()],
      tracks: [track()]
    })
    await flushPromises()

    const mapInstance = leafletMock.mapInstances[0]
    mapInstance.fitBounds.mockClear()

    await buttonByText(wrapper, 'Morning Walk').trigger('click')
    await flushPromises()

    expect(mapInstance.fitBounds).toHaveBeenCalledWith(expect.anything(), {
      padding: [44, 44],
      maxZoom: 15
    })
  })

  it('keeps track editing actions for permitted users', async () => {
    const uploadTrack = vi.fn().mockResolvedValue(track())
    const updateTrack = vi.fn().mockResolvedValue(track({ title: 'Updated Track' }))
    const deleteTrack = vi.fn().mockResolvedValue(undefined)
    const wrapper = mountPanel({
      tracks: [track()],
      canEditTracks: true,
      uploadTrack,
      updateTrack,
      deleteTrack
    })

    await buttonByText(wrapper, 'Track hochladen').trigger('click')
    const file = new File(['<gpx />'], 'morning-walk.gpx', { type: 'application/gpx+xml' })
    wrapper.findComponent({ name: 'FileUpload' }).vm.$emit('select', { files: [file] })
    await flushPromises()
    await buttonByText(wrapper, 'Speichern').trigger('click')
    await flushPromises()

    expect(uploadTrack).toHaveBeenCalledWith({
      file,
      title: 'morning-walk',
      activityType: 'HIKING'
    })

    await wrapper.find('button[aria-label="Bearbeiten"]').trigger('click')
    await wrapper.findAll('input')[1].setValue('Updated Track')
    await buttonByText(wrapper, 'Speichern').trigger('click')
    await flushPromises()

    expect(updateTrack).toHaveBeenCalledWith('track-1', {
      title: 'Updated Track',
      activityType: 'HIKING'
    })

    await wrapper.find('button[aria-label="Löschen"]').trigger('click')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(deleteTrack).toHaveBeenCalledWith('track-1')
    expect(wrapper.emitted('changed')).toHaveLength(3)
  })
})

function mountPanel(props: Partial<InstanceType<typeof MemoryContextPanel>['$props']> = {}) {
  return mount(MemoryContextPanel, {
    props: {
      tenantName: 'friends',
      media: [],
      tracks: [],
      canEditTracks: false,
      ...props
    },
    global: {
      stubs: {
        ...primeStubs(),
        MediaLightbox: {
          template: '<div data-testid="lightbox" />'
        }
      }
    }
  })
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
    posterUrl: null,
    imageWidth: 1200,
    imageHeight: 800,
    createdAt: '2026-05-24T10:00:00Z'
  }
}

function track(overrides: Partial<MemoryTrack> = {}): MemoryTrack {
  return {
    id: 'track-1',
    memoryId: 'memory-1',
    title: 'Morning Walk',
    activityType: 'HIKING',
    originalFilename: 'morning-walk.gpx',
    contentUrl: '/api/memories/memory-1/tracks/track-1/content',
    distanceMeters: 1234,
    pointCount: 42,
    bounds: {
      minLatitude: 52.1,
      minLongitude: 13.1,
      maxLatitude: 52.2,
      maxLongitude: 13.2
    },
    geometry: {
      type: 'LineString',
      coordinates: [
        [13.1, 52.1],
        [13.2, 52.2]
      ]
    },
    createdAt: '2026-05-24T10:00:00Z',
    updatedAt: '2026-05-24T10:00:00Z',
    ...overrides
  }
}

function buttonByText(wrapper: VueWrapper, text: string) {
  const button = wrapper.findAll('button').find((candidate) => candidate.text().includes(text))
  if (!button) throw new Error(`Button not found: ${text}`)
  return button
}

function primeStubs() {
  return {
    Button: {
      props: ['label', 'disabled', 'type'],
      emits: ['click'],
      template:
        '<button :type="type || \'button\'" :aria-label="$attrs[\'aria-label\']" :disabled="disabled" @click="$emit(\'click\', $event)">{{ label }}<slot /></button>'
    },
    Dialog: {
      props: ['visible', 'header'],
      emits: ['update:visible'],
      template: '<section v-if="visible !== false"><h3>{{ header }}</h3><slot /></section>'
    },
    FileUpload: {
      name: 'FileUpload',
      template: '<div data-testid="file-upload"><slot /></div>'
    },
    InputText: {
      props: ['modelValue'],
      emits: ['update:modelValue'],
      template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />'
    },
    Select: {
      props: ['modelValue', 'options'],
      emits: ['update:modelValue'],
      template:
        '<select :value="modelValue" @change="$emit(\'update:modelValue\', $event.target.value)"><option v-for="option in options" :key="option.value" :value="option.value">{{ option.label }}</option></select>'
    }
  }
}
