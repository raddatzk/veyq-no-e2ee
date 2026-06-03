import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { authState } from '../../../authState'
import type { MemoryMedia, MemoryTrack } from '../api'
import TrackMap from './TrackMap.vue'

const leafletMock = vi.hoisted(() => ({
  fitBounds: vi.fn(),
  map: vi.fn(),
  marker: vi.fn(),
  markerOn: vi.fn(),
  divIcon: vi.fn(),
  polyline: vi.fn(),
  tileLayer: vi.fn()
}))

vi.mock('leaflet', () => ({
  default: {
    map: vi.fn((element: HTMLElement, options: object) => {
      leafletMock.map(element, options)
      return {
        fitBounds: leafletMock.fitBounds,
        invalidateSize: vi.fn(),
        latLngToLayerPoint: vi.fn(([latitude, longitude]: [number, number]) => {
          const point = { x: longitude * 100, y: latitude * 100 }
          return {
            ...point,
            distanceTo(other: { x: number; y: number }) {
              return Math.hypot(point.x - other.x, point.y - other.y)
            }
          }
        }),
        off: vi.fn(),
        on: vi.fn(),
        remove: vi.fn()
      }
    }),
    divIcon: vi.fn((options: object) => {
      leafletMock.divIcon(options)
      return options
    }),
    marker: vi.fn((position: [number, number], options: object) => {
      leafletMock.marker(position, options)
      const marker = {
        addTo: vi.fn(() => ({ remove: vi.fn() })),
        on: vi.fn((event: string, handler: () => void) => {
          leafletMock.markerOn(event, handler)
          return marker
        }),
        remove: vi.fn()
      }
      return marker
    }),
    latLngBounds: vi.fn((positions: [number, number][]) => ({ positions })),
    polyline: vi.fn((positions: [number, number][], options: object) => {
      leafletMock.polyline(positions, options)
      return {
        addTo: vi.fn(() => ({ remove: vi.fn() })),
        remove: vi.fn()
      }
    }),
    tileLayer: vi.fn((url: string, options: object) => {
      leafletMock.tileLayer(url, options)
      return {
        addTo: vi.fn(() => ({ remove: vi.fn() })),
        remove: vi.fn()
      }
    })
  }
}))

describe('TrackMap', () => {
  afterEach(() => {
    window.localStorage.clear()
    authState.isAuthenticated = false
    authState.user = null
    authState.userLabel = ''
    authState.tenants = []
    leafletMock.fitBounds.mockClear()
    leafletMock.map.mockClear()
    leafletMock.marker.mockClear()
    leafletMock.markerOn.mockClear()
    leafletMock.divIcon.mockClear()
    leafletMock.polyline.mockClear()
    leafletMock.tileLayer.mockClear()
  })

  it('enables direct scroll wheel touch and control zooming', async () => {
    mountTrackMap()
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

  it('renders a Leaflet polyline using latitude longitude positions', async () => {
    mountTrackMap()
    await flushPromises()

    expect(leafletMock.polyline).toHaveBeenCalledWith(
      [
        [52.51, 13.39],
        [52.52, 13.405],
        [52.53, 13.42]
      ],
      expect.objectContaining({ color: 'var(--app-success)' })
    )
    expect(leafletMock.fitBounds).toHaveBeenCalledWith(
      expect.objectContaining({
        positions: [
          [52.51, 13.39],
          [52.52, 13.405],
          [52.53, 13.42]
        ]
      }),
      expect.objectContaining({ maxZoom: 15 })
    )
  })

  it('renders route without OpenStreetMap tiles until consent is granted', async () => {
    const wrapper = mountTrackMap()
    await flushPromises()

    expect(leafletMock.polyline).toHaveBeenCalled()
    expect(leafletMock.tileLayer).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('External map tiles are disabled')

    const osmButton = wrapper.findAll('button').find((button) => button.text() === 'Load OpenStreetMap')
    if (!osmButton) throw new Error('OpenStreetMap consent button not found')
    await osmButton.trigger('click')
    await flushPromises()

    expect(window.localStorage.getItem('veyq:osm-tiles-enabled')).toBe('true')
    expect(leafletMock.tileLayer).toHaveBeenCalledWith(
      'https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',
      expect.objectContaining({ maxZoom: 19 })
    )
  })

  it('renders selected tracks and memory photos on the map while reset fits the tracks', async () => {
    const wrapper = mountTrackMap({
      tracks: [track(), track({ id: 'track-2', title: 'Evening Walk' })],
      media: [media()]
    })
    await flushPromises()

    expect(leafletMock.polyline.mock.calls.length).toBeGreaterThanOrEqual(2)
    expect(leafletMock.marker).toHaveBeenCalledWith(
      [52.515, 13.4],
      expect.objectContaining({ title: 'Checkpoint' })
    )

    leafletMock.fitBounds.mockClear()
    await wrapper.find('.track-map-reset').trigger('click')

    expect(leafletMock.fitBounds).toHaveBeenCalledWith(
      expect.objectContaining({
        positions: expect.arrayContaining([
          [52.51, 13.39],
          [52.53, 13.42]
        ])
      }),
      expect.objectContaining({ maxZoom: 15 })
    )
  })

  it('groups nearby memory photos as clickable map markers', async () => {
    mountTrackMap({
      media: [
        media({ id: 'media-1', title: 'First checkpoint', latitude: 52.515, longitude: 13.4 }),
        media({ id: 'media-2', title: 'Second checkpoint', latitude: 52.516, longitude: 13.401 })
      ]
    })
    await flushPromises()

    expect(leafletMock.marker).toHaveBeenCalledWith(
      [52.515, 13.4],
      expect.objectContaining({ title: '2 media' })
    )
    const markerOptions = leafletMock.divIcon.mock.calls.at(-1)?.[0] as { html: HTMLElement }
    expect(markerOptions.html.classList.contains('memory-media-map-marker-cluster')).toBe(true)
    expect(markerOptions.html.querySelector('.memory-media-map-marker-badge')?.textContent).toBe('2')
    expect(leafletMock.markerOn).toHaveBeenCalledWith('click', expect.any(Function))
  })
})

function mountTrackMap(props: Partial<InstanceType<typeof TrackMap>['$props']> = {}) {
  return mount(TrackMap, {
    props: { track: track(), ...props },
    global: {
      mocks: {
        $t: (key: string) =>
          ({
            'map.externalTilesAllow': 'Load OpenStreetMap',
            'map.externalTilesTitle': 'External map tiles are disabled',
            'map.resetView': 'Reset'
          })[key] || key
      }
    }
  })
}

function track(overrides: Partial<MemoryTrack> = {}): MemoryTrack {
  return {
    id: 'track-1',
    memoryId: 'memory-1',
    title: 'Morning Walk',
    activityType: 'WALKING',
    originalFilename: 'morning-walk.gpx',
    contentUrl: '/api/memories/memory-1/tracks/track-1/content',
    distanceMeters: 2400,
    ascentMeters: 120,
    descentMeters: 80,
    pointCount: 3,
    startedAt: '2026-05-03T08:00:00Z',
    endedAt: '2026-05-03T09:00:00Z',
    bounds: {
      minLatitude: 52.51,
      minLongitude: 13.39,
      maxLatitude: 52.53,
      maxLongitude: 13.42
    },
    geometry: {
      type: 'LineString',
      coordinates: [
        [13.39, 52.51],
        [13.405, 52.52],
        [13.42, 52.53]
      ]
    },
    createdAt: '2026-05-24T10:00:00Z',
    updatedAt: '2026-05-24T10:00:00Z',
    ...overrides
  }
}

function media(
  overrides: Partial<MemoryMedia> & { latitude?: number; longitude?: number } = {}
): MemoryMedia {
  const { latitude, longitude, ...mediaOverrides } = overrides
  return {
    id: 'media-1',
    memoryId: 'memory-1',
    title: 'Checkpoint',
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
    gpsLatitude: latitude ?? 52.515,
    gpsLongitude: longitude ?? 13.4,
    highlight: false,
    ...mediaOverrides
  }
}
