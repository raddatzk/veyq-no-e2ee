import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createAlbum, createFolder } from '../../api/explorerClient'
import { groups } from '../../api/groupClient'
import { authState } from '../../authState'
import CreateLibraryItemDialog from './CreateLibraryItemDialog.vue'

vi.mock('../../api/explorerClient', () => ({
  createAlbum: vi.fn(),
  createFolder: vi.fn()
}))

vi.mock('../../api/groupClient', () => ({
  groups: vi.fn()
}))

const createAlbumMock = vi.mocked(createAlbum)
const createFolderMock = vi.mocked(createFolder)
const groupsMock = vi.mocked(groups)

beforeEach(() => {
  vi.resetAllMocks()
  authState.user = {
    userId: 1,
    email: 'kevin@example.test',
    displayName: 'Kevin',
    serverRole: 'ServerMember'
  }
})

function mountDialog(parentPath = '/') {
  return mount(CreateLibraryItemDialog, {
    props: {
      visible: false,
      type: 'folder',
      tenantName: 'friends',
      tenantDisplayName: 'Friends',
      parentPath
    },
    global: {
      stubs: {
        Dialog: { template: '<div><slot /></div>' },
        Button: { template: '<button type="submit"><slot />{{ label }}</button>', props: ['label'] },
        InputText: {
          props: ['modelValue'],
          emits: ['update:modelValue'],
          template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />'
        },
        Select: {
          props: ['modelValue', 'options', 'optionLabel', 'optionValue'],
          emits: ['update:modelValue'],
          template: '<select :value="modelValue" @change="$emit(\'update:modelValue\', $event.target.value)"><option v-for="option in options" :key="option[optionValue]" :value="option[optionValue]">{{ option[optionLabel] }}</option></select>'
        },
        Message: { template: '<div><slot /></div>' }
      }
    }
  })
}

describe('CreateLibraryItemDialog', () => {
  it('loads root groups and submits selected group scope', async () => {
    groupsMock.mockResolvedValue([{ id: 1, name: 'family', displayName: 'Family' }])
    createFolderMock.mockResolvedValue({
      id: 'folder-1',
      name: 'trip',
      displayName: 'Trip',
      path: '/@family/trip',
      ownerBadge: null
    })
    const wrapper = mountDialog()

    await wrapper.setProps({ visible: true })
    await flushPromises()
    expect(groupsMock).toHaveBeenCalledWith('friends')

    await wrapper.findAll('input')[0].setValue('Trip')
    await wrapper.find('select').setValue('GROUP:family')
    await wrapper.find('form').trigger('submit')

    expect(createFolderMock).toHaveBeenCalledWith('friends', {
      parentPath: '/',
      displayName: 'Trip',
      name: 'trip',
      scope: { type: 'GROUP', groupName: 'family' }
    })
  })

  it('does not load groups or send scope when creating below a parent folder', async () => {
    createAlbumMock.mockResolvedValue({
      id: 'album-1',
      name: 'child',
      displayName: 'Child',
      path: '/parent/child',
      coverMediaId: null,
      ownerBadge: null
    })
    const wrapper = mountDialog('/parent')
    await wrapper.setProps({ visible: true, type: 'album' })
    await flushPromises()

    expect(groupsMock).not.toHaveBeenCalled()
    await wrapper.findAll('input')[0].setValue('Child')
    await wrapper.find('form').trigger('submit')

    expect(createAlbumMock).toHaveBeenCalledWith('friends', {
      parentPath: '/parent',
      displayName: 'Child',
      name: 'child',
      scope: null
    })
  })
})
