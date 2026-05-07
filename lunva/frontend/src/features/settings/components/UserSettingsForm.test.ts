import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import UserSettingsForm from './UserSettingsForm.vue'
import { getMySettings, updateMySettings } from '../api'

vi.mock('../api', () => ({
  changeMyPassword: vi.fn(),
  getMySettings: vi.fn(),
  updateMySettings: vi.fn()
}))

const getMySettingsMock = vi.mocked(getMySettings)
const updateMySettingsMock = vi.mocked(updateMySettings)

describe('UserSettingsForm', () => {
  afterEach(() => {
    getMySettingsMock.mockReset()
    updateMySettingsMock.mockReset()
  })

  it('shows pending email and confirmation message after profile save', async () => {
    getMySettingsMock.mockResolvedValue(userSettings('owner@example.test'))
    updateMySettingsMock.mockResolvedValue(
      userSettings('owner@example.test', 'changed@example.test')
    )

    const wrapper = mount(UserSettingsForm, { global: { stubs: formStubs() } })
    await flushPromises()

    const inputs = wrapper.findAll('input')
    await inputs[1].setValue('changed@example.test')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(updateMySettingsMock).toHaveBeenCalledWith({
      displayName: 'Owner',
      email: 'changed@example.test'
    })
    expect(wrapper.text()).toContain('Neue Email wartet auf Bestaetigung: changed@example.test')
    expect(wrapper.text()).toContain('Bitte bestaetige die neue Email-Adresse.')
  })
})

function userSettings(email: string, pendingEmail?: string) {
  return {
    userId: 1,
    email,
    displayName: 'Owner',
    serverRole: 'ServerOwner',
    pendingEmail
  } as const
}

function formStubs() {
  return {
    Button: { template: '<button><slot />{{ label }}</button>', props: ['label'] },
    InputText: {
      template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
      props: ['modelValue']
    },
    Message: { template: '<div><slot /></div>' },
    Password: {
      template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
      props: ['modelValue']
    },
    ProgressSpinner: { template: '<div />' }
  }
}
