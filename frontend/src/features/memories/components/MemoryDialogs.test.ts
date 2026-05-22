import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  deleteMemory,
  removeMemoryCover,
  setMemoryCover,
  updateMemory,
  type Memory
} from '../api'
import DeleteMemoryDialog from './DeleteMemoryDialog.vue'
import EditMemoryDialog from './EditMemoryDialog.vue'
import MemoryCoverDialog from './MemoryCoverDialog.vue'

vi.mock('../api', () => ({
  deleteMemory: vi.fn(),
  removeMemoryCover: vi.fn(),
  setMemoryCover: vi.fn(),
  updateMemory: vi.fn()
}))

const deleteMemoryMock = vi.mocked(deleteMemory)
const removeMemoryCoverMock = vi.mocked(removeMemoryCover)
const setMemoryCoverMock = vi.mocked(setMemoryCover)
const updateMemoryMock = vi.mocked(updateMemory)

describe('Memory detail dialogs', () => {
  afterEach(() => {
    vi.resetAllMocks()
  })

  it('updates memory display metadata and emits the updated memory', async () => {
    const updated = memory({
      displayName: 'Updated Trip',
      name: 'updated-trip',
      path: '/updated-trip'
    })
    updateMemoryMock.mockResolvedValue(updated)
    const saved = vi.fn()

    const wrapper = mount(EditMemoryDialog, {
      props: {
        visible: true,
        tenantName: 'tenant',
        memory: memory(),
        onSaved: saved
      },
      global: { stubs: dialogStubs() }
    })

    await wrapper.findAll('input')[0].setValue('Updated Trip')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(updateMemoryMock).toHaveBeenCalledWith('tenant', 'mem-1', {
      displayName: 'Updated Trip',
      name: 'updated-trip'
    })
    expect(saved).toHaveBeenCalledWith(updated)
  })

  it('deletes a memory after explicit confirmation', async () => {
    deleteMemoryMock.mockResolvedValue()

    const wrapper = mount(DeleteMemoryDialog, {
      props: {
        visible: true,
        tenantName: 'tenant',
        memory: memory()
      },
      global: { stubs: dialogStubs() }
    })

    await wrapper
      .findAll('button')
      .find((button) => button.text().includes('Memory löschen'))!
      .trigger('click')
    await flushPromises()

    expect(deleteMemoryMock).toHaveBeenCalledWith('tenant', 'mem-1')
    expect(wrapper.emitted('deleted')).toHaveLength(1)
  })

  it('uploads a standalone cover image', async () => {
    const file = new File(['cover'], 'cover.jpg', { type: 'image/jpeg' })
    const updated = memory({ coverUrl: '/api/tenant/memories/mem-1/cover/content' })
    setMemoryCoverMock.mockResolvedValue(updated)
    const saved = vi.fn()

    const wrapper = mount(MemoryCoverDialog, {
      props: {
        visible: true,
        tenantName: 'tenant',
        memory: memory({ coverMediaId: 'media-1' }),
        onSaved: saved
      },
      global: { stubs: dialogStubs() }
    })

    wrapper.findComponent({ name: 'FileUpload' }).vm.$emit('select', { files: [file] })
    await flushPromises()
    await wrapper
      .findAll('button')
      .find((button) => button.text().includes('Cover speichern'))!
      .trigger('click')
    await flushPromises()

    expect(setMemoryCoverMock).toHaveBeenCalledWith('tenant', 'mem-1', { file })
    expect(saved).toHaveBeenCalledWith(updated)
  })

  it('removes an existing cover', async () => {
    const updated = memory({ coverMediaId: null })
    removeMemoryCoverMock.mockResolvedValue(updated)
    const saved = vi.fn()

    const wrapper = mount(MemoryCoverDialog, {
      props: {
        visible: true,
        tenantName: 'tenant',
        memory: memory({ coverUrl: '/api/tenant/memories/mem-1/cover/content' }),
        onSaved: saved
      },
      global: { stubs: dialogStubs() }
    })

    await wrapper
      .findAll('button')
      .find((button) => button.text().includes('Cover entfernen'))!
      .trigger('click')
    await flushPromises()

    expect(removeMemoryCoverMock).toHaveBeenCalledWith('tenant', 'mem-1')
    expect(saved).toHaveBeenCalledWith(updated)
  })
})

function dialogStubs() {
  return {
    Button: {
      template:
        '<button :type="type || \'button\'" :disabled="disabled" @click="$emit(\'click\', $event)">{{ label }}</button>',
      emits: ['click'],
      props: ['disabled', 'label', 'type']
    },
    Dialog: {
      template: '<div v-if="visible"><slot /></div>',
      props: ['visible']
    },
    InputText: {
      template:
        '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
      props: ['modelValue']
    },
    FileUpload: {
      name: 'FileUpload',
      template: '<button type="button">Bild auswählen</button>',
      emits: ['select']
    },
    Message: { template: '<div><slot /></div>' }
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
    childCount: 0,
    mediaCount: 0,
    scope: null,
    ...overrides
  }
}
