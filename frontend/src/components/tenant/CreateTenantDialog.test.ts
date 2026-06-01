import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import CreateTenantDialog from './CreateTenantDialog.vue'
import { createTenant, tenants } from '../../api/tenantClient'
import { authState } from '../../authState'

vi.mock('../../api/tenantClient', () => ({
  createTenant: vi.fn(),
  tenants: vi.fn()
}))

const createTenantMock = vi.mocked(createTenant)
const tenantsMock = vi.mocked(tenants)

describe('CreateTenantDialog', () => {
  afterEach(() => {
    createTenantMock.mockReset()
    tenantsMock.mockReset()
    authState.tenants = []
  })

  it('requires an owner email before creating the tenant', async () => {
    createTenantMock.mockResolvedValue({
      id: 1,
      name: 'friends',
      displayName: 'Friends',
      domain: 'friends.example.test',
      status: 'Pending',
      invitedOwnerEmail: 'owner@example.test'
    })
    tenantsMock.mockResolvedValue([])

    const wrapper = mount(CreateTenantDialog, {
      props: { visible: true, 'onUpdate:visible': () => undefined },
      global: { stubs: dialogStubs() }
    })

    expect(wrapper.text()).not.toContain('Adresse')

    const inputs = wrapper.findAll('input')
    await inputs[0].setValue('Friends')
    await inputs[1].setValue('friends.example.test')

    const createButton = buttonByText(wrapper, 'Erstellen')
    expect(createButton.attributes('disabled')).toBeDefined()
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(createTenantMock).not.toHaveBeenCalled()

    await inputs[2].setValue('owner@example.test')
    expect(createButton.attributes('disabled')).toBeUndefined()
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(createTenantMock).toHaveBeenCalledWith(
      'friends',
      'Friends',
      'friends.example.test',
      'owner@example.test'
    )
  })
})

function buttonByText(wrapper: ReturnType<typeof mount>, text: string) {
  const button = wrapper.findAll('button').find((candidate) => candidate.text() === text)
  if (!button) throw new Error(`Button not found: ${text}`)
  return button
}

function dialogStubs() {
  return {
    Button: {
      template:
        '<button :type="type || \'button\'" :disabled="disabled" @click="$emit(\'click\')">{{ label }}</button>',
      props: ['label', 'type', 'disabled']
    },
    Dialog: {
      template: '<div v-if="visible">{{ header }}<slot /></div>',
      props: ['visible', 'header']
    },
    InputText: {
      template:
        '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
      props: ['modelValue']
    },
    Message: { template: '<div><slot /></div>' }
  }
}
