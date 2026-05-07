import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import { deleteLibraryItem } from '../../api/explorerClient'
import DeleteExplorerItemDialog from './DeleteExplorerItemDialog.vue'

vi.mock('../../api/explorerClient', () => ({
  deleteLibraryItem: vi.fn()
}))

const deleteLibraryItemMock = vi.mocked(deleteLibraryItem)

describe('DeleteExplorerItemDialog', () => {
  it('requires the item display name before deleting', async () => {
    deleteLibraryItemMock.mockResolvedValue()
    const wrapper = mount(DeleteExplorerItemDialog, {
      props: {
        visible: true,
        tenantName: 'friends',
        type: 'folder' as const,
        item: {
          id: 'folder-1',
          name: 'trip',
          displayName: 'Trip Photos',
          path: '/trip',
          ownerBadge: null
        },
        'onUpdate:visible': (value: boolean) => wrapper.setProps({ visible: value })
      },
      global: {
        stubs: {
          Button: {
            props: ['disabled', 'label'],
            template: '<button :disabled="disabled" @click="$emit(\'click\')">{{ label }}</button>'
          },
          Dialog: {
            props: ['visible'],
            template: '<div v-if="visible"><slot /></div>'
          },
          InputText: {
            props: ['modelValue'],
            template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />'
          },
          Message: { template: '<div><slot /></div>' }
        }
      }
    })

    const deleteButton = () => wrapper.findAll('button').find((button) => button.text() === 'Löschen')!
    expect(deleteButton().attributes('disabled')).toBeDefined()

    await wrapper.find('input').setValue('Trip Photos')
    await deleteButton().trigger('click')

    expect(deleteLibraryItemMock).toHaveBeenCalledWith('friends', 'folder', '/trip')
  })
})
