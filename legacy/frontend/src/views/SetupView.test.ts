import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import SetupView from './SetupView.vue'
import { registerServerOwner } from '../features/admin/setupServer/setupServer'
import { downloadKitPdf } from '../features/account/emergencyKit/emergencyKit'
import { serverState } from '../serverState'

vi.mock('../features/admin/setupServer/setupServer', () => ({
  registerServerOwner: vi.fn()
}))

vi.mock('../features/account/emergencyKit/emergencyKit', () => ({
  downloadKitPdf: vi.fn()
}))

const registerServerOwnerMock = vi.mocked(registerServerOwner)
const downloadKitPdfMock = vi.mocked(downloadKitPdf)

describe('SetupView Emergency Kit', () => {
  afterEach(() => {
    registerServerOwnerMock.mockReset()
    downloadKitPdfMock.mockReset()
    serverState.setupCompleted = false
  })

  it('keeps sign-in disabled until the Emergency Kit download is triggered and confirmed', async () => {
    const { wrapper, router } = await registeredKitView()
    const proceedButton = buttonByText(wrapper, 'Weiter zur Anmeldung')
    const confirmCheckbox = wrapper.find('input[type="checkbox"]')

    expect(wrapper.text()).not.toContain('SECRET-KEY')
    expect(proceedButton.attributes('disabled')).toBeDefined()
    expect(confirmCheckbox.attributes('disabled')).toBeDefined()

    await buttonByText(wrapper, 'Emergency Kit (PDF) herunterladen').trigger('click')
    await flushPromises()

    expect(downloadKitPdfMock).toHaveBeenCalledWith(
      { instance: '', email: 'owner@example.test', secretKey: 'SECRET-KEY' },
      'Owner'
    )
    expect(wrapper.text()).toContain('Emergency-Kit-Download wurde gestartet.')
    expect(confirmCheckbox.attributes('disabled')).toBeUndefined()
    expect(proceedButton.attributes('disabled')).toBeDefined()

    await confirmCheckbox.setValue(true)
    expect(proceedButton.attributes('disabled')).toBeUndefined()

    await proceedButton.trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.name).toBe('login')
  })
})

async function registeredKitView() {
  registerServerOwnerMock.mockResolvedValue({
    user: { id: '1', email: 'owner@example.test', serverRole: 'ServerOwner' },
    emergencyKit: { secretKey: 'SECRET-KEY' }
  })
  downloadKitPdfMock.mockResolvedValue()

  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/setup', name: 'setup', component: SetupView },
      { path: '/login', name: 'login', component: { template: '<div />' } }
    ]
  })
  await router.push('/setup')
  await router.isReady()

  const wrapper = mount(SetupView, { global: { plugins: [router], stubs: viewStubs() } })
  const inputs = wrapper.findAll('input')
  await inputs[0].setValue('Owner')
  await inputs[1].setValue('owner@example.test')
  await inputs[2].setValue('correct horse battery staple')
  await wrapper.find('form').trigger('submit')
  await flushPromises()

  expect(wrapper.text()).toContain('Lade dein Emergency Kit herunter')
  return { wrapper, router }
}

function buttonByText(wrapper: ReturnType<typeof mount>, text: string) {
  const button = wrapper.findAll('button').find((candidate) => candidate.text() === text)
  if (!button) throw new Error(`Missing button: ${text}`)
  return button
}

function viewStubs() {
  return {
    Button: {
      template:
        '<button :type="type || \'button\'" :disabled="disabled || loading" @click="$emit(\'click\')">{{ label }}</button>',
      props: ['disabled', 'label', 'loading', 'type']
    },
    Card: { template: '<section><slot name="title" /><slot name="content" /></section>' },
    InputText: {
      template:
        '<input :type="type || \'text\'" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
      props: ['modelValue', 'type']
    },
    Message: { template: '<div><slot /></div>' },
    Password: {
      template:
        '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
      props: ['modelValue']
    }
  }
}
