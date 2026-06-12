import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import UserSettingsForm from './UserSettingsForm.vue'
import {
  changeMyPassword,
  createTotpEnrollment,
  createMyPasskey,
  deleteMyAvatar,
  deleteMyAccountWithPasskey,
  deleteMyAccountWithPassword,
  deleteMyPasskeyWithPasskey,
  deleteMyPassword,
  disableTotp,
  enableTotp,
  getMySettings,
  listMyPasskeys,
  updateMyAvatar,
  updateMyPasskey,
  updateMySettings
} from '../api'
import { setLocale } from '../../../i18n'
import { authState } from '../../../authState'

const pushMock = vi.fn()
vi.mock('vue-router', () => ({
  useRouter: () => ({ push: pushMock })
}))

vi.mock('../api', () => ({
  changeMyPassword: vi.fn(),
  createTotpEnrollment: vi.fn(),
  createMyPasskey: vi.fn(),
  deleteMyAvatar: vi.fn(),
  deleteMyAccountWithPasskey: vi.fn(),
  deleteMyAccountWithPassword: vi.fn(),
  deleteMyPasskeyWithPasskey: vi.fn(),
  deleteMyPassword: vi.fn(),
  disableTotp: vi.fn(),
  enableTotp: vi.fn(),
  getMySettings: vi.fn(),
  listMyPasskeys: vi.fn(),
  updateMyAvatar: vi.fn(),
  updateMyPasskey: vi.fn(),
  updateMySettings: vi.fn()
}))

vi.mock('qrcode', () => ({
  toDataURL: vi.fn().mockResolvedValue('data:image/png;base64,qr-code')
}))

const createMyPasskeyMock = vi.mocked(createMyPasskey)
const changeMyPasswordMock = vi.mocked(changeMyPassword)
const createTotpEnrollmentMock = vi.mocked(createTotpEnrollment)
const deleteMyAvatarMock = vi.mocked(deleteMyAvatar)
const deleteMyAccountWithPasskeyMock = vi.mocked(deleteMyAccountWithPasskey)
const deleteMyAccountWithPasswordMock = vi.mocked(deleteMyAccountWithPassword)
const deleteMyPasskeyWithPasskeyMock = vi.mocked(deleteMyPasskeyWithPasskey)
const deleteMyPasswordMock = vi.mocked(deleteMyPassword)
const disableTotpMock = vi.mocked(disableTotp)
const enableTotpMock = vi.mocked(enableTotp)
const getMySettingsMock = vi.mocked(getMySettings)
const listMyPasskeysMock = vi.mocked(listMyPasskeys)
const updateMyAvatarMock = vi.mocked(updateMyAvatar)
const updateMyPasskeyMock = vi.mocked(updateMyPasskey)
const updateMySettingsMock = vi.mocked(updateMySettings)

describe('UserSettingsForm', () => {
  afterEach(() => {
    changeMyPasswordMock.mockReset()
    createTotpEnrollmentMock.mockReset()
    createMyPasskeyMock.mockReset()
    deleteMyAvatarMock.mockReset()
    deleteMyAccountWithPasskeyMock.mockReset()
    deleteMyAccountWithPasswordMock.mockReset()
    deleteMyPasskeyWithPasskeyMock.mockReset()
    deleteMyPasswordMock.mockReset()
    disableTotpMock.mockReset()
    enableTotpMock.mockReset()
    getMySettingsMock.mockReset()
    listMyPasskeysMock.mockReset()
    updateMyAvatarMock.mockReset()
    updateMyPasskeyMock.mockReset()
    updateMySettingsMock.mockReset()
    authState.isAuthenticated = false
    authState.user = null
    authState.userLabel = ''
    authState.tenants = []
    pushMock.mockReset()
    window.localStorage.clear()
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

  it('renders password TOTP and passkeys as two login method panels', async () => {
    getMySettingsMock.mockResolvedValue(userSettings('owner@example.test'))
    listMyPasskeysMock.mockResolvedValue({ passkeys: [passkey(7, 'Laptop')] })

    const wrapper = mount(UserSettingsForm, { global: { stubs: formStubs() } })
    await flushPromises()

    const panels = wrapper.findAll('.login-method-panel')
    expect(panels).toHaveLength(2)
    expect(panels[0].text()).toContain('Passwort')
    expect(panels[0].text()).toContain('Authenticator-App')
    expect(panels[1].text()).toContain('Passkeys')
  })

  it('uploads an avatar and updates auth state', async () => {
    authState.user = userSettings('owner@example.test')
    getMySettingsMock.mockResolvedValue(userSettings('owner@example.test'))
    listMyPasskeysMock.mockResolvedValue({ passkeys: [] })
    updateMyAvatarMock.mockResolvedValue(userSettings('owner@example.test', undefined, '/api/users/me/avatar?v=1'))
    const file = new File(['avatar'], 'avatar.png', { type: 'image/png' })

    const wrapper = mount(UserSettingsForm, { global: { stubs: formStubs() } })
    await flushPromises()

    wrapper.findComponent({ name: 'FileUpload' }).vm.$emit('select', { files: [file] })
    await flushPromises()

    expect(updateMyAvatarMock).toHaveBeenCalledWith(file)
    expect(authState.user.avatarUrl).toBe('/api/users/me/avatar?v=1')
    expect(wrapper.text()).toContain('Profilbild wurde gespeichert.')
  })

  it('deletes an avatar and clears auth state', async () => {
    authState.user = userSettings('owner@example.test', undefined, '/api/users/me/avatar?v=1')
    getMySettingsMock.mockResolvedValue(userSettings('owner@example.test', undefined, '/api/users/me/avatar?v=1'))
    listMyPasskeysMock.mockResolvedValue({ passkeys: [] })
    deleteMyAvatarMock.mockResolvedValue(userSettings('owner@example.test'))

    const wrapper = mount(UserSettingsForm, { global: { stubs: formStubs() } })
    await flushPromises()

    await wrapper.findAll('button').find((button) => button.attributes('aria-label') === 'Profilbild entfernen')!.trigger('click')
    await flushPromises()

    expect(deleteMyAvatarMock).toHaveBeenCalledWith()
    expect(authState.user.avatarUrl).toBeUndefined()
    expect(wrapper.text()).toContain('Profilbild wurde gelöscht.')
  })

  it('opens external content dialog and stores grouped provider consent for the signed-in user', async () => {
    authState.isAuthenticated = true
    authState.user = {
      userId: 1,
      email: 'owner@example.test',
      displayName: 'Owner',
      permalink: 'owner',
      serverRole: 'ServerOwner',
      passkeyCount: 0,
      hasPassword: true
    }
    getMySettingsMock.mockResolvedValue(userSettings('owner@example.test'))
    listMyPasskeysMock.mockResolvedValue({ passkeys: [] })

    const wrapper = mount(UserSettingsForm, { global: { stubs: formStubs() } })
    await flushPromises()

    expect(wrapper.text()).toContain('Externe Inhalte')
    await wrapper.findAll('button').find((button) => button.text() === 'Externe Inhalte verwalten')!.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('Karten')
    expect(wrapper.text()).toContain('OpenStreetMap')

    const checkboxes = wrapper.findAll('input[type="checkbox"]')
    expect(checkboxes).toHaveLength(1)
    await checkboxes[0].setValue(true)

    expect(window.localStorage.getItem('veyq:user:1:osm-tiles-enabled')).toBe('true')
    expect(window.localStorage.getItem('veyq:osm-tiles-enabled')).toBeNull()

    await checkboxes[0].setValue(false)

    expect(window.localStorage.getItem('veyq:user:1:osm-tiles-enabled')).toBeNull()
  })

  it('does not show external content dialog before opening it', async () => {
    getMySettingsMock.mockResolvedValue(userSettings('owner@example.test'))
    listMyPasskeysMock.mockResolvedValue({ passkeys: [] })

    const wrapper = mount(UserSettingsForm, { global: { stubs: formStubs() } })
    await flushPromises()

    expect(wrapper.text()).toContain('Externe Inhalte verwalten')
    expect(wrapper.text()).not.toContain('Karten')
  })

  it('changes an existing password with the current password', async () => {
    changeMyPasswordMock.mockResolvedValue()
    getMySettingsMock.mockResolvedValue(userSettings('owner@example.test', undefined, undefined, true))
    listMyPasskeysMock.mockResolvedValue({ passkeys: [] })

    const wrapper = mount(UserSettingsForm, { global: { stubs: formStubs() } })
    await flushPromises()

    expect(wrapper.text()).toContain('Aktuelles Passwort')
    expect(wrapper.text()).toContain('Passwort ändern')

    const passwordForm = wrapper.findAll('form')[1]
    const passwordInputs = passwordForm.findAll('input')
    await passwordInputs[0].setValue('current-password')
    await passwordInputs[1].setValue('new-password-123')
    await passwordForm.trigger('submit')
    await flushPromises()

    expect(changeMyPasswordMock).toHaveBeenCalledWith({
      currentPassword: 'current-password',
      newPassword: 'new-password-123'
    })
  })

  it('sets the first password without requiring the current password', async () => {
    changeMyPasswordMock.mockResolvedValue()
    getMySettingsMock
      .mockResolvedValueOnce(userSettings('owner@example.test', undefined, undefined, false))
      .mockResolvedValueOnce(userSettings('owner@example.test', undefined, undefined, true))
    listMyPasskeysMock.mockResolvedValue({ passkeys: [passkey(7, 'Laptop')] })

    const wrapper = mount(UserSettingsForm, { global: { stubs: formStubs() } })
    await flushPromises()

    expect(wrapper.text()).not.toContain('Aktuelles Passwort')
    expect(wrapper.text()).toContain('Passwort setzen')

    const passwordForm = wrapper.findAll('form')[1]
    await passwordForm.find('input').setValue('new-password-123')
    await passwordForm.trigger('submit')
    await flushPromises()

    expect(changeMyPasswordMock).toHaveBeenCalledWith({
      newPassword: 'new-password-123'
    })
  })

  it('hides TOTP setup for passkey-only accounts', async () => {
    getMySettingsMock.mockResolvedValue(userSettings('owner@example.test', undefined, undefined, false))
    listMyPasskeysMock.mockResolvedValue({ passkeys: [passkey(7, 'Laptop')] })

    const wrapper = mount(UserSettingsForm, { global: { stubs: formStubs() } })
    await flushPromises()

    expect(wrapper.text()).not.toContain('Authenticator-App')
    expect(wrapper.text()).not.toContain('TOTP einrichten')
    expect(wrapper.text()).not.toContain('Setze zuerst ein Passwort')

    expect(createTotpEnrollmentMock).not.toHaveBeenCalled()
  })

  it('enables TOTP after showing the enrollment QR code', async () => {
    authState.user = userSettings('owner@example.test')
    getMySettingsMock.mockResolvedValue(userSettings('owner@example.test'))
    listMyPasskeysMock.mockResolvedValue({ passkeys: [] })
    createTotpEnrollmentMock.mockResolvedValue({
      secret: 'JBSWY3DPEHPK3PXP',
      provisioningUri: 'otpauth://totp/veyq:owner@example.test?secret=JBSWY3DPEHPK3PXP',
      issuer: 'veyq',
      accountName: 'owner@example.test',
      digits: 6,
      periodSeconds: 30
    })
    enableTotpMock.mockResolvedValue(userSettings('owner@example.test', undefined, undefined, true, true))

    const wrapper = mount(UserSettingsForm, { global: { stubs: formStubs() } })
    await flushPromises()

    await wrapper.findAll('button').find((button) => button.text() === 'TOTP einrichten')!.trigger('click')
    await flushPromises()

    expect(createTotpEnrollmentMock).toHaveBeenCalledWith()
    expect(wrapper.text()).toContain('Scanne den QR-Code')
    expect(wrapper.find('.totp-qr-code img').attributes('src')).toBe('data:image/png;base64,qr-code')
    expect(wrapper.text()).not.toContain('Provisioning-Link')

    const totpForm = wrapper.findAll('form')[2]
    const inputs = totpForm.findAll('input')
    await inputs[0].setValue('current-password')
    await inputs[1].setValue('123456')
    await totpForm.trigger('submit')
    await flushPromises()

    expect(enableTotpMock).toHaveBeenCalledWith('current-password', 'JBSWY3DPEHPK3PXP', '123456')
    expect(wrapper.text()).toContain('TOTP ist für Passwort-Logins aktiv.')
    expect(wrapper.text()).not.toContain('TOTP wurde aktiviert.')
    expect(authState.user?.totpEnabled).toBe(true)
  })

  it('shows manual TOTP secret only after fallback request', async () => {
    getMySettingsMock.mockResolvedValue(userSettings('owner@example.test'))
    listMyPasskeysMock.mockResolvedValue({ passkeys: [] })
    createTotpEnrollmentMock.mockResolvedValue({
      secret: 'JBSWY3DPEHPK3PXP',
      provisioningUri: 'otpauth://totp/veyq:owner@example.test?secret=JBSWY3DPEHPK3PXP',
      issuer: 'veyq',
      accountName: 'owner@example.test',
      digits: 6,
      periodSeconds: 30
    })

    const wrapper = mount(UserSettingsForm, { global: { stubs: formStubs() } })
    await flushPromises()
    await wrapper.findAll('button').find((button) => button.text() === 'TOTP einrichten')!.trigger('click')
    await flushPromises()

    expect(wrapper.text()).not.toContain('Provisioning-Link')
    expect(wrapper.text()).not.toContain('Secret')

    await wrapper.findAll('button').find((button) => button.text() === 'Manuell einrichten')!.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('Secret')
    expect(wrapper.text()).not.toContain('Provisioning-Link')
    expect(wrapper.findAll('input').some((input) => input.element.value === 'JBSWY3DPEHPK3PXP')).toBe(true)
    expect(wrapper.findAll('input').some((input) => input.element.value.startsWith('otpauth://'))).toBe(false)
  })

  it('disables TOTP with password and current code', async () => {
    authState.user = userSettings('owner@example.test', undefined, undefined, true, true)
    getMySettingsMock.mockResolvedValue(userSettings('owner@example.test', undefined, undefined, true, true))
    listMyPasskeysMock.mockResolvedValue({ passkeys: [] })
    disableTotpMock.mockResolvedValue(userSettings('owner@example.test', undefined, undefined, true, false))

    const wrapper = mount(UserSettingsForm, { global: { stubs: formStubs() } })
    await flushPromises()

    expect(wrapper.text()).toContain('TOTP ist für Passwort-Logins aktiv.')
    expect(wrapper.text()).not.toContain('Authenticator-Code')
    await wrapper.findAll('button').find((button) => button.text() === 'TOTP deaktivieren')!.trigger('click')
    await flushPromises()

    const totpForm = wrapper.findAll('form')[2]
    const inputs = totpForm.findAll('input')
    await inputs[0].setValue('current-password')
    await inputs[1].setValue('654321')
    await totpForm.trigger('submit')
    await flushPromises()

    expect(disableTotpMock).toHaveBeenCalledWith('current-password', '654321')
    expect(wrapper.text()).toContain('TOTP ist für Passwort-Logins noch nicht eingerichtet.')
    expect(wrapper.text()).not.toContain('TOTP wurde deaktiviert.')
    expect(authState.user?.totpEnabled).toBe(false)
  })

  it('deletes password only when a passkey fallback exists and clears password TOTP state', async () => {
    authState.user = userSettings('owner@example.test', undefined, undefined, true, true)
    getMySettingsMock.mockResolvedValue(userSettings('owner@example.test', undefined, undefined, true, true))
    listMyPasskeysMock.mockResolvedValue({ passkeys: [passkey(7, 'Laptop')] })
    deleteMyPasswordMock.mockResolvedValue(userSettings('owner@example.test', undefined, undefined, false, false))

    const wrapper = mount(UserSettingsForm, { global: { stubs: formStubs() } })
    await flushPromises()

    await wrapper.findAll('button').find((button) => button.text() === 'Passwort löschen')!.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('TOTP wird dabei ebenfalls deaktiviert')

    await wrapper.findAll('input').at(-1)!.setValue('current-password')
    await wrapper.findAll('form').at(-1)!.trigger('submit')
    await flushPromises()

    expect(deleteMyPasswordMock).toHaveBeenCalledWith('current-password')
    expect(authState.user?.hasPassword).toBe(false)
    expect(authState.user?.totpEnabled).toBe(false)
    expect(wrapper.text()).toContain('Passwort wurde gelöscht.')
    expect(wrapper.text()).not.toContain('Authenticator-App')
  })

  it('does not show password delete without passkey fallback', async () => {
    getMySettingsMock.mockResolvedValue(userSettings('owner@example.test'))
    listMyPasskeysMock.mockResolvedValue({ passkeys: [] })

    const wrapper = mount(UserSettingsForm, { global: { stubs: formStubs() } })
    await flushPromises()

    expect(wrapper.text()).not.toContain('Passwort löschen')
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
    listMyPasskeysMock.mockResolvedValue({ passkeys: [passkey(7, 'Laptop', 'memories.raddatz.me')] })

    const wrapper = mount(UserSettingsForm, { global: { stubs: formStubs() } })
    await flushPromises()

    expect(wrapper.findAll('input').some((input) => input.element.value === 'Laptop')).toBe(true)
    expect(wrapper.text()).toContain('memories.raddatz.me')
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

  it('deletes a passkey after passkey confirmation', async () => {
    authState.user = userSettings('owner@example.test')
    getMySettingsMock.mockResolvedValue(userSettings('owner@example.test'))
    listMyPasskeysMock.mockResolvedValue({ passkeys: [passkey(7, 'Laptop')] })
    deleteMyPasskeyWithPasskeyMock.mockResolvedValue(userSettings('owner@example.test'))

    const wrapper = mount(UserSettingsForm, { global: { stubs: formStubs() } })
    await flushPromises()

    await wrapper.findAll('button').find((button) => button.attributes('aria-label') === 'Passkey löschen')!.trigger('click')
    await flushPromises()

    expect(deleteMyPasskeyWithPasskeyMock).toHaveBeenCalledWith(7)
    expect(wrapper.text()).toContain('Passkey wurde gelöscht.')
    expect(authState.user?.passkeyCount).toBe(0)
  })

  it('does not allow deleting the last passkey without a password', async () => {
    getMySettingsMock.mockResolvedValue(userSettings('owner@example.test', undefined, undefined, false))
    listMyPasskeysMock.mockResolvedValue({ passkeys: [passkey(7, 'Laptop')] })

    const wrapper = mount(UserSettingsForm, { global: { stubs: formStubs() } })
    await flushPromises()

    expect(wrapper.text()).toContain('Dieser Passkey ist deine letzte Login-Methode')
    const deleteButton = wrapper.findAll('button').find((button) => button.attributes('aria-label') === 'Passkey löschen')!
    expect(deleteButton.attributes('disabled')).toBeDefined()

    await deleteButton.trigger('click')

    expect(deleteMyPasskeyWithPasskeyMock).not.toHaveBeenCalled()
  })

  it('deletes the current account with password confirmation', async () => {
    authState.isAuthenticated = true
    deleteMyAccountWithPasswordMock.mockResolvedValue()
    getMySettingsMock.mockResolvedValue(userSettings('owner@example.test'))
    listMyPasskeysMock.mockResolvedValue({ passkeys: [] })

    const wrapper = mount(UserSettingsForm, { global: { stubs: formStubs() } })
    await flushPromises()

    await wrapper.findAll('button').find((button) => button.text() === 'Account löschen')!.trigger('click')
    await flushPromises()
    await wrapper.findAll('input').at(-2)!.setValue('owner@example.test')
    await wrapper.findAll('input').at(-1)!.setValue('current-password')
    await wrapper.findAll('form').at(-1)!.trigger('submit')
    await flushPromises()

    expect(deleteMyAccountWithPasswordMock).toHaveBeenCalledWith('current-password')
    expect(authState.isAuthenticated).toBe(false)
  })

  it('shows progress and blocks duplicate account deletion while pending', async () => {
    let resolveDelete: () => void = () => undefined
    authState.isAuthenticated = true
    deleteMyAccountWithPasswordMock.mockReturnValue(new Promise<void>((resolve) => {
      resolveDelete = resolve
    }))
    getMySettingsMock.mockResolvedValue(userSettings('owner@example.test'))
    listMyPasskeysMock.mockResolvedValue({ passkeys: [] })

    const wrapper = mount(UserSettingsForm, { global: { stubs: formStubs() } })
    await flushPromises()

    await wrapper.findAll('button').find((button) => button.text() === 'Account löschen')!.trigger('click')
    await flushPromises()
    await wrapper.findAll('input').at(-2)!.setValue('owner@example.test')
    await wrapper.findAll('input').at(-1)!.setValue('current-password')
    await wrapper.findAll('form').at(-1)!.trigger('submit')
    await flushPromises()

    const buttons = wrapper.findAll('button')
    expect(buttons.find((button) => button.text() === 'Abbrechen')?.attributes('disabled')).toBeDefined()
    expect(buttons.reverse().find((button) => button.text() === 'Account löschen')?.attributes('data-loading')).toBe('true')

    await wrapper.findAll('form').at(-1)!.trigger('submit')
    expect(deleteMyAccountWithPasswordMock).toHaveBeenCalledTimes(1)

    resolveDelete()
    await flushPromises()

    expect(authState.isAuthenticated).toBe(false)
  })
})

function userSettings(
  email: string,
  pendingEmail?: string,
  avatarUrl?: string,
  hasPassword = true,
  totpEnabled?: boolean
) {
  return {
    userId: 1,
    email,
    displayName: 'Owner',
    permalink: 'owner',
    serverRole: 'ServerOwner',
    passkeyCount: 0,
    hasPassword,
    totpEnabled,
    avatarUrl,
    pendingEmail
  } as const
}

function passkey(passkeyId: number, displayName: string, domain?: string) {
  return {
    passkeyId,
    displayName,
    createdAt: '2026-05-25T10:00:00Z',
    lastUsedAt: undefined,
    domain
  }
}

function formStubs() {
  return {
    Button: {
      template:
        '<button :type="type || \'button\'" :aria-label="ariaLabel" :disabled="disabled" :data-loading="String(loading)" @click="$emit(\'click\')"><slot />{{ label }}</button>',
      props: ['ariaLabel', 'disabled', 'label', 'loading', 'type']
    },
    InputText: {
      template: '<input :value="modelValue" :disabled="disabled" @input="$emit(\'update:modelValue\', $event.target.value)" />',
      props: ['disabled', 'modelValue']
    },
    Message: { template: '<div><slot /></div>' },
    FileUpload: {
      name: 'FileUpload',
      template: '<button type="button">{{ chooseLabel }}</button>',
      props: ['chooseLabel']
    },
    Dialog: {
      template: '<div v-if="visible">{{ header }}<slot /></div>',
      props: ['visible', 'header']
    },
    Password: {
      template: '<input :value="modelValue" :disabled="disabled" @input="$emit(\'update:modelValue\', $event.target.value)" />',
      props: ['disabled', 'modelValue']
    },
    ProgressSpinner: { template: '<div />' }
  }
}
