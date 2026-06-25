import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import MemoryFolderOverview from './MemoryFolderOverview.vue'
import { deleteFolder } from '../api'
import type { MemoryNavigationContextResponse } from '../api'

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return {
    ...actual,
    deleteFolder: vi.fn()
  }
})

describe('MemoryFolderOverview', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('opens a confirmation before deleting the current folder', async () => {
    vi.mocked(deleteFolder).mockResolvedValue(undefined)
    const wrapper = mountOverview()

    await wrapper.find('.memory-folder-overview-action').trigger('click')

    expect(deleteFolder).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('Ordner löschen')
    expect(wrapper.text()).toContain('alle Unterordner')
    expect(wrapper.text()).toContain('Ordnername zur Bestätigung')
  })

  it('requires the current folder name before deleting the current folder', async () => {
    vi.mocked(deleteFolder).mockResolvedValue(undefined)
    const wrapper = mountOverview()

    await wrapper.find('.memory-folder-overview-action').trigger('click')
    const deleteButton = () =>
      wrapper.findAll('button').find((button) => button.text().includes('Ordner löschen'))!
    expect(deleteButton().attributes('disabled')).toBeDefined()

    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(deleteFolder).not.toHaveBeenCalled()

    await wrapper.find('input').setValue('Wrong folder')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(deleteFolder).not.toHaveBeenCalled()

    await wrapper.find('input').setValue('Archive')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(deleteFolder).toHaveBeenCalledWith('family', '/archive')
    expect(wrapper.emitted('deletedFolder')).toBeTruthy()
  })

  it('shows an error when deleting the current folder fails', async () => {
    vi.mocked(deleteFolder).mockRejectedValue(new Error('Delete failed'))
    const wrapper = mountOverview()

    await wrapper.find('.memory-folder-overview-action').trigger('click')
    await wrapper.find('input').setValue('Archive')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain('Delete failed')
    expect(wrapper.emitted('deletedFolder')).toBeFalsy()
  })
})

function mountOverview() {
  return mount(MemoryFolderOverview, {
    props: {
      tenantName: 'family',
      navigation: navigation(),
      memories: []
    },
    global: {
      stubs: primeStubs(),
      directives: {
        tooltip: {}
      }
    }
  })
}

function navigation(): MemoryNavigationContextResponse {
  return {
    location: {
      kind: 'FOLDER',
      path: '/archive',
      displayName: 'Archive'
    },
    breadcrumbs: [],
    scopeRoots: [],
    folders: [],
    memories: [],
    favoriteMemories: []
  } as MemoryNavigationContextResponse
}

function primeStubs() {
  return {
    Button: {
      props: ['label', 'type', 'disabled', 'loading'],
      emits: ['click'],
      template:
        '<button v-bind="$attrs" :type="type || `button`" :disabled="disabled || loading" @click="$emit(\'click\', $event)">{{ label }}<slot /></button>'
    },
    Dialog: {
      props: ['visible'],
      emits: ['update:visible'],
      template: '<div v-if="visible"><slot /><slot name="footer" /></div>'
    },
    Message: {
      template: '<div><slot /></div>'
    },
    InputText: {
      props: ['modelValue'],
      emits: ['update:modelValue'],
      template:
        '<input v-bind="$attrs" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />'
    }
  }
}
