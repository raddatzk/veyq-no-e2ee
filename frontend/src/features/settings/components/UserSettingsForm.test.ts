import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import UserSettingsForm from './UserSettingsForm.vue'
import {
  createMyPasskey,
  deleteMyPasskey,
  getMySettings,
  listMyPasskeys,
  updateMyPasskey,
  updateMySettings
} from '../api'
import { setLocale } from '../../../i18n'

vi.mock('../api', () => ({
  changeMyPassword: vi.fn(),
  createMyPasskey: vi.fn(),
  deleteMyPasskey: vi.fn(),
  getMySettings: vi.fn(),
  listMyPasskeys: vi.fn(),
  updateMyPasskey: vi.fn(),
  updateMySettings: vi.fn()
}))

const createMyPasskeyMock = vi.mocked(createMyPasskey)
const deleteMyPasskeyMock = vi.mocked(deleteMyPasskey)
const getMySettingsMock = vi.mocked(getMySettings)
const listMyPasskeysMock = vi.mocked(listMyPasskeys)
const updateMyPasskeyMock = vi.mocked(updateMyPasskey)
const updateMySettingsMock = vi.mocked(updateMySettings)

describe('UserSettingsForm', () => {
  afterEach(() => {
    createMyPasskeyMock.mockReset()
    deleteMyPasskeyMock.mockReset()
    getMySettingsMock.mockReset()
    listMyPasskeysMock.mockReset()
    updateMyPasskeyMock.mockReset()
    updateMySettingsMock.mockReset()
    setLocale('de')
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

  it('adds a passkey and asks to confirm the suggested name', async () => {
    getMySettingsMock.mockResolvedValue(userSettings('owner@example.test'))
    listMyPasskeysMock.mockResolvedValue({ passkeys: [] })
    createMyPasskeyMock.mockResolvedValue(passkey(9, 'Apple Passwords'))
    updateMyPasskeyMock.mockResolvedValue(passkey(9, 'MacBook Touch ID'))

    const wrapper = mount(UserSettingsForm, { global: { stubs: formStubs() } })
    await flushPromises()

    await wrapper.findAll('button').find((button) => button.text() === 'Passkey hinzufügen')!.trigger('click')
    await flushPromises()

    expect(createMyPasskeyMock).toHaveBeenCalledWith()
    expect(wrapper.text()).toContain('Passkey benennen')
    expect(wrapper.text()).toContain('Der Name hilft dir später')

    await wrapper.findAll('input').at(-1)!.setValue('  MacBook Touch ID  ')
    await wrapper.findAll('button').filter((button) => button.text() === 'Speichern').at(-1)!.trigger('click')
    await flushPromises()

    expect(updateMyPasskeyMock).toHaveBeenCalledWith(9, 'MacBook Touch ID')
    expect(wrapper.text()).toContain('Passkey-Name wurde gespeichert.')
  })

  it('renders existing passkey names as editable fields', async () => {
    getMySettingsMock.mockResolvedValue(userSettings('owner@example.test'))
    listMyPasskeysMock.mockResolvedValue({ passkeys: [passkey(7, 'Laptop')] })

    const wrapper = mount(UserSettingsForm, { global: { stubs: formStubs() } })
    await flushPromises()

    expect(wrapper.findAll('input').some((input) => input.element.value === 'Laptop')).toBe(true)
  })

  it('requires a passkey name before renaming a passkey', async () => {
    getMySettingsMock.mockResolvedValue(userSettings('owner@example.test'))
    listMyPasskeysMock.mockResolvedValue({ passkeys: [passkey(7, 'Laptop')] })

    const wrapper = mount(UserSettingsForm, { global: { stubs: formStubs() } })
    await flushPromises()

    const passkeyInput = wrapper.findAll('input').find((input) => input.element.value === 'Laptop')!
    await passkeyInput.setValue('   ')
    await wrapper.findAll('button').find((button) => button.attributes('aria-label') === 'Passkey umbenennen')!.trigger('click')
    await flushPromises()

    expect(updateMyPasskeyMock).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('Bitte gib einen Passkey-Namen ein.')
  })

  it('renames a passkey with trimmed input and updates the list', async () => {
    getMySettingsMock.mockResolvedValue(userSettings('owner@example.test'))
    listMyPasskeysMock.mockResolvedValue({ passkeys: [passkey(7, 'Laptop')] })
    updateMyPasskeyMock.mockResolvedValue(passkey(7, 'MacBook Touch ID'))

    const wrapper = mount(UserSettingsForm, { global: { stubs: formStubs() } })
    await flushPromises()

    const passkeyInput = wrapper.findAll('input').find((input) => input.element.value === 'Laptop')!
    await passkeyInput.setValue('  MacBook Touch ID  ')
    await wrapper.findAll('button').find((button) => button.attributes('aria-label') === 'Passkey umbenennen')!.trigger('click')
    await flushPromises()

    expect(updateMyPasskeyMock).toHaveBeenCalledWith(7, 'MacBook Touch ID')
    expect(wrapper.findAll('input').some((input) => input.element.value === 'MacBook Touch ID')).toBe(true)
    expect(wrapper.text()).toContain('Passkey wurde umbenannt.')
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

function passkey(passkeyId: number, displayName: string) {
  return {
    passkeyId,
    displayName,
    createdAt: '2026-05-25T10:00:00Z',
    lastUsedAt: undefined
  }
}

function formStubs() {
  return {
    Button: {
      template:
        '<button :type="type || \'button\'" :aria-label="ariaLabel" @click="$emit(\'click\')"><slot />{{ label }}</button>',
      props: ['ariaLabel', 'label', 'type']
    },
    InputText: {
      template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
      props: ['modelValue']
    },
    Message: { template: '<div><slot /></div>' },
    Dialog: {
      template: '<div v-if="visible">{{ header }}<slot /></div>',
      props: ['visible', 'header']
    },
    Password: {
      template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
      props: ['modelValue']
    },
    ProgressSpinner: { template: '<div />' }
  }
}
