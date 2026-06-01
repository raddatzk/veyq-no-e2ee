import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import type { MemoryTrack } from '../api'
import MemoryTracksPanel from './MemoryTracksPanel.vue'

describe('MemoryTracksPanel', () => {
  it('shows an actionable empty state for editable memories', () => {
    const wrapper = mount(MemoryTracksPanel, {
      props: {
        tracks: [],
        canEdit: true
      },
      global: { stubs: primeStubs() }
    })

    expect(wrapper.text()).toContain('Noch keine Tracks in dieser Memory.')
    expect(wrapper.text()).toContain('Lade GPX-Dateien hoch, um Routen mit Aktivitätstyp und Distanz anzuzeigen.')
    expect(buttonByText(wrapper, 'Track hochladen').exists()).toBe(true)
  })

  it('uploads a selected GPX file with inferred title and default activity type', async () => {
    const uploadTrack = vi.fn().mockResolvedValue(track())
    const wrapper = mount(MemoryTracksPanel, {
      props: {
        tracks: [],
        canEdit: true,
        uploadTrack
      },
      global: { stubs: primeStubs() }
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
    expect(wrapper.emitted('changed')).toHaveLength(1)
  })

  it('updates and deletes existing tracks', async () => {
    const updateTrack = vi.fn().mockResolvedValue(track({ title: 'Updated Track' }))
    const deleteTrack = vi.fn().mockResolvedValue(undefined)
    const wrapper = mount(MemoryTracksPanel, {
      props: {
        tracks: [track()],
        canEdit: true,
        updateTrack,
        deleteTrack
      },
      global: { stubs: primeStubs() }
    })

    await wrapper.find('button[aria-label="Bearbeiten"]').trigger('click')
    await wrapper.find('input').setValue('Updated Track')
    await buttonByText(wrapper, 'Speichern').trigger('click')
    await flushPromises()

    expect(updateTrack).toHaveBeenCalledWith('track-1', {
      title: 'Updated Track',
      activityType: 'HIKING'
    })

    await wrapper.find('button[aria-label="Löschen"]').trigger('click')
    expect(wrapper.text()).toContain('Track "Weekend Hike" löschen?')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(deleteTrack).toHaveBeenCalledWith('track-1')
    expect(wrapper.emitted('changed')).toHaveLength(2)
  })

  it('shows progress and blocks duplicate track deletion while pending', async () => {
    let resolveDelete: () => void = () => undefined
    const deleteTrack = vi.fn().mockReturnValue(new Promise<void>((resolve) => {
      resolveDelete = resolve
    }))
    const wrapper = mount(MemoryTracksPanel, {
      props: {
        tracks: [track()],
        canEdit: true,
        deleteTrack
      },
      global: { stubs: primeStubs() }
    })

    await wrapper.find('button[aria-label="Löschen"]').trigger('click')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(buttonByText(wrapper, 'Abbrechen').attributes('disabled')).toBeDefined()
    expect(wrapper.findAll('button').reverse().find((button) => button.text().includes('Löschen'))?.attributes('data-loading')).toBe('true')

    await wrapper.find('form').trigger('submit')
    expect(deleteTrack).toHaveBeenCalledTimes(1)

    resolveDelete()
    await flushPromises()

    expect(wrapper.emitted('changed')).toHaveLength(1)
  })

  it('shows distance duration ascent and descent for tracks', () => {
    const wrapper = mount(MemoryTracksPanel, {
      props: {
        tracks: [
          track({
            distanceMeters: 2400,
            ascentMeters: 180,
            descentMeters: 95,
            startedAt: '2026-05-03T08:00:00Z',
            endedAt: '2026-05-03T09:35:00Z'
          })
        ],
        canEdit: false
      },
      global: { stubs: primeStubs() }
    })

    expect(wrapper.text()).toContain('2.4 km')
    expect(wrapper.text()).toContain('1 h 35 min')
    expect(wrapper.text()).toContain('180 m')
    expect(wrapper.text()).toContain('95 m')
    expect(wrapper.text()).not.toContain('Punkte')
  })
})

function track(overrides: Partial<MemoryTrack> = {}): MemoryTrack {
  return {
    id: 'track-1',
    memoryId: 'memory-1',
    title: 'Weekend Hike',
    activityType: 'HIKING',
    originalFilename: 'weekend-hike.gpx',
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
      props: ['label', 'disabled', 'loading', 'type'],
      emits: ['click'],
      template:
        '<button :type="type || \'button\'" :aria-label="$attrs[\'aria-label\']" :disabled="disabled" :data-loading="String(loading)" @click="$emit(\'click\', $event)">{{ label }}<slot /></button>'
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
    },
    TrackMap: {
      template: '<div data-testid="track-map" />'
    }
  }
}
