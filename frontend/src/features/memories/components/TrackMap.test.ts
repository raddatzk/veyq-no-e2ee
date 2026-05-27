import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { authState } from '../../../authState'
import type { MemoryTrack } from '../api'
import TrackMap from './TrackMap.vue'

const leafletMock = vi.hoisted(() => ({
  fitBounds: vi.fn(),
  map: vi.fn(),
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
        remove: vi.fn()
      }
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
      expect.objectContaining({ color: '#0f766e' })
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

    await wrapper.get('button').trigger('click')
    await flushPromises()

    expect(window.localStorage.getItem('veyq:osm-tiles-enabled')).toBe('true')
    expect(leafletMock.tileLayer).toHaveBeenCalledWith(
      'https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',
      expect.objectContaining({ maxZoom: 19 })
    )
  })
})

function mountTrackMap() {
  return mount(TrackMap, {
    props: { track: track() },
    global: {
      mocks: {
        $t: (key: string) =>
          ({
            'map.externalTilesAllow': 'Load OpenStreetMap',
            'map.externalTilesTitle': 'External map tiles are disabled'
          })[key] || key
      }
    }
  })
}

function track(): MemoryTrack {
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
    updatedAt: '2026-05-24T10:00:00Z'
  }
}
