// @vitest-environment happy-dom
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'

vi.mock('../tree', () => ({
  createFolder: vi.fn().mockResolvedValue('node-1'),
  createMemory: vi.fn().mockResolvedValue({ nodeId: 'node-1', memoryKey: new Uint8Array(32) }),
  renameNode: vi.fn().mockResolvedValue(undefined),
}))

import NodeMutationDialog from './NodeMutationDialog.vue'
import { createFolder, createMemory, renameNode } from '../tree'

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  messages: {
    en: {
      common: { create: 'Create', cancel: 'Cancel' },
      memory: {
        createFolderTitle: 'Create a folder', createMemoryTitle: 'Create a Memory', renameTitle: 'Rename',
        nameLabel: 'Name', save: 'Save', slugTaken: 'Taken', illegalStructure: 'Illegal', saveFailed: 'Failed',
      },
    },
  },
})

const stubs = {
  Dialog: { template: '<div><slot /></div>' },
  InputText: {
    props: ['modelValue'],
    template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
  },
  Button: { props: ['type', 'label'], template: '<button :type="type || \'button\'" @click="$emit(\'click\', $event)">{{ label }}</button>' },
  Message: { template: '<div class="msg"><slot /></div>' },
}

const SCOPE = '11111111-1111-1111-1111-111111111111'
const KEY = new Uint8Array(32).fill(7)

function mountDialog(props: Record<string, unknown>) {
  return mount(NodeMutationDialog, {
    props: { scopeId: SCOPE, scopeKey: KEY, ...props } as never,
    global: { plugins: [i18n], stubs },
  })
}

beforeEach(() => vi.clearAllMocks())

describe('NodeMutationDialog', () => {
  it('create-folder calls createFolder with the parent + closes + emits done', async () => {
    const wrapper = mountDialog({ mode: 'create-folder', parentId: 'parent-1' })
    await wrapper.find('input').setValue('Trips')
    await wrapper.find('form').trigger('submit')
    await Promise.resolve()

    expect(createFolder).toHaveBeenCalledWith(SCOPE, KEY, 'Trips', 'parent-1')
    expect(wrapper.emitted('done')).toBeTruthy()
    expect(wrapper.emitted('close')).toBeTruthy()
  })

  it('create-memory at the root passes parentId null', async () => {
    const wrapper = mountDialog({ mode: 'create-memory', parentId: null })
    await wrapper.find('input').setValue('Italy')
    await wrapper.find('form').trigger('submit')
    await Promise.resolve()
    expect(createMemory).toHaveBeenCalledWith(SCOPE, KEY, 'Italy', null)
  })

  it('rename calls renameNode with the node id + parent', async () => {
    const node = { id: 'n9', parentId: 'p1', kind: 'FOLDER', name: 'Old', orderIndex: 0, wrappedContentKey: null, wrappedMeta: null }
    const wrapper = mountDialog({ mode: 'rename', node })
    expect((wrapper.find('input').element as HTMLInputElement).value).toBe('Old') // prefilled
    await wrapper.find('input').setValue('New')
    await wrapper.find('form').trigger('submit')
    await Promise.resolve()
    expect(renameNode).toHaveBeenCalledWith(SCOPE, KEY, { id: 'n9', parentId: 'p1' }, 'New')
  })

  it('maps a 409 to the slug-taken message and stays open', async () => {
    vi.mocked(createFolder).mockRejectedValueOnce({ response: { status: 409 } })
    const wrapper = mountDialog({ mode: 'create-folder', parentId: null })
    await wrapper.find('input').setValue('Dupe')
    await wrapper.find('form').trigger('submit')
    await Promise.resolve()
    await Promise.resolve()

    expect(wrapper.text()).toContain('Taken')
    expect(wrapper.emitted('done')).toBeFalsy()
  })
})
