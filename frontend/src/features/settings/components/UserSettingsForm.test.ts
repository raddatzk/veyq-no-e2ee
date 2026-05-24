import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import UserSettingsForm from './UserSettingsForm.vue'
import { createMyPasskey, deleteMyPasskey, getMySettings, listMyPasskeys, updateMySettings } from '../api'
import { setLocale } from '../../../i18n'

vi.mock('../api', () => ({
  changeMyPassword: vi.fn(),
  createMyPasskey: vi.fn(),
  deleteMyPasskey: vi.fn(),
  getMySettings: vi.fn(),
  listMyPasskeys: vi.fn(),
  updateMySettings: vi.fn()
}))

const createMyPasskeyMock = vi.mocked(createMyPasskey)
const deleteMyPasskeyMock = vi.mocked(deleteMyPasskey)
const getMySettingsMock = vi.mocked(getMySettings)
const listMyPasskeysMock = vi.mocked(listMyPasskeys)
const updateMySettingsMock = vi.mocked(updateMySettings)

describe('UserSettingsForm', () => {
  afterEach(() => {
    createMyPasskeyMock.mockReset()
    deleteMyPasskeyMock.mockReset()
    getMySettingsMock.mockReset()
    listMyPasskeysMock.mockReset()
    updateMySettingsMock.mockReset()
  })

  it('shows pending email and confirmation message after profile save', async () => {
    getMySettingsMock.mockResolvedValue(userSettings('owner@example.test'))
    listMyPasskeysMock.mockResolvedValue({ passkeys: [] })
    updateMySettingsMock.mockResolvedValue(
      userSettings('owner@example.test', 'changed@example.test')
    )

    const wrapper = mount(UserSettingsForm, { global: { stubs: formStubs() } })
    await flushPromises()

    const inputs = wrapper.findAll('input')
    await inputs[2].setValue('changed@example.test')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(updateMySettingsMock).toHaveBeenCalledWith({
      displayName: 'Owner',
      permalink: 'owner',
      email: 'changed@example.test'
    })
    expect(wrapper.text()).toContain('Neue E-Mail wartet auf Bestätigung: changed@example.test')
    expect(wrapper.text()).toContain('Bitte bestätige die neue E-Mail-Adresse.')
  })

  it('renders profile settings labels in English', async () => {
    setLocale('en')
    getMySettingsMock.mockResolvedValue(userSettings('owner@example.test'))
    listMyPasskeysMock.mockResolvedValue({ passkeys: [] })

    const wrapper = mount(UserSettingsForm, { global: { stubs: formStubs() } })
    await flushPromises()

    expect(wrapper.text()).toContain('My settings')
    expect(wrapper.text()).toContain('Profile and login')
  })
})

function userSettings(email: string, pendingEmail?: string) {
  return {
    userId: 1,
    email,
    displayName: 'Owner',
    permalink: 'owner',
    serverRole: 'ServerOwner',
    passkeyCount: 0,
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
