// @vitest-environment happy-dom
// uc.admin.create-tenant (web Slice A) — the tenant-admin screen UI flow: it loads + lists tenants, and on
// a child `created` event reloads and renders the new tenant as a PENDING row with resend/reassign/revoke
// actions; revoke is confirm-gated. Component-level acceptance for `create-pending-tenant` (the manual
// browser smoke, now automated). The crypto-free Slice-B activation (TenantJoinView) stays a gap.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'

const push = vi.fn()
vi.mock('vue-router', () => ({ useRouter: () => ({ push }) }))

vi.mock('../features/admin/createTenant/tenants', () => ({
  listTenants: vi.fn(),
  resendInvite: vi.fn().mockResolvedValue({}),
  reassignInvite: vi.fn().mockResolvedValue({}),
  revokeTenant: vi.fn().mockResolvedValue(undefined),
  tenantError: vi.fn().mockResolvedValue({ status: 0, code: null }),
}))

import TenantsView from './TenantsView.vue'
import { listTenants, revokeTenant, resendInvite } from '../features/admin/createTenant/tenants'

const i18n = createI18n({ legacy: false, locale: 'en', missingWarn: false, fallbackWarn: false, messages: { en: {} } })

// A stub that lets a test fire the dialog's `created` event without driving the real dialog form.
const CreateTenantDialogStub = {
  props: ['visible'],
  emits: ['created', 'update:visible'],
  template: '<button class="dlg-created" @click="$emit(\'created\')">created</button>',
}

const stubs = {
  CreateTenantDialog: CreateTenantDialogStub,
  Card: { template: '<div><slot name="content" /></div>' },
  Dialog: { props: ['visible'], template: '<div v-if="visible"><slot /></div>' },
  InputText: { props: ['modelValue'], template: '<input :value="modelValue" />' },
  Button: { props: ['label'], template: '<button @click="$emit(\'click\', $event)">{{ label }}</button>' },
  Message: { template: '<div class="msg"><slot /></div>' },
}

const PENDING = {
  tenantId: 't-1', name: 'Acme', domain: 'acme.example', ownerEmail: 'owner@acme.example', status: 'PENDING',
}

function mountView() {
  return mount(TenantsView, { global: { plugins: [i18n], stubs } })
}

describe('TenantsView (uc.admin.create-tenant, web Slice A)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(listTenants).mockResolvedValue([])
  })

  it('lists nothing on an empty instance', async () => {
    const wrapper = mountView()
    await flushPromises()
    expect(wrapper.find('.tenant-row').exists()).toBe(false)
    expect(listTenants).toHaveBeenCalledTimes(1)
  })

  it('reloads on `created` and renders the new tenant as a PENDING row with invite actions', async () => {
    const wrapper = mountView()
    await flushPromises() // initial empty load

    // the create dialog reports success → the view reloads and the backend now returns the PENDING tenant
    vi.mocked(listTenants).mockResolvedValue([PENDING as never])
    await wrapper.find('.dlg-created').trigger('click')
    await flushPromises()

    const row = wrapper.find('.tenant-row')
    expect(row.exists()).toBe(true)
    expect(row.text()).toContain('Acme')
    expect(row.find('.status--pending').exists()).toBe(true)
    expect(listTenants).toHaveBeenCalledTimes(2)
    // a PENDING tenant exposes the three invite-lifecycle actions
    expect(row.findAll('.row-actions button')).toHaveLength(3)
  })

  it('revoke is confirm-gated: cancelling does nothing', async () => {
    vi.mocked(listTenants).mockResolvedValue([PENDING as never])
    const wrapper = mountView()
    await flushPromises()

    window.confirm = () => false
    await wrapper.findAll('.row-actions button')[2].trigger('click') // revoke (cancelled)
    await flushPromises()
    expect(revokeTenant).not.toHaveBeenCalled()

    window.confirm = () => true
    await wrapper.findAll('.row-actions button')[2].trigger('click') // revoke (confirmed)
    await flushPromises()
    expect(revokeTenant).toHaveBeenCalledWith('t-1')
  })

  it('redirects to login when the list call is unauthenticated (401)', async () => {
    const { tenantError } = await import('../features/admin/createTenant/tenants')
    vi.mocked(listTenants).mockRejectedValueOnce(new Error('401'))
    vi.mocked(tenantError).mockResolvedValueOnce({ status: 401, code: null })
    mountView()
    await flushPromises()
    expect(push).toHaveBeenCalledWith({ name: 'login' })
  })

  it('resend delegates to the facade with the tenant id', async () => {
    vi.mocked(listTenants).mockResolvedValue([PENDING as never])
    const wrapper = mountView()
    await flushPromises()
    await wrapper.findAll('.row-actions button')[0].trigger('click') // resend
    await flushPromises()
    expect(resendInvite).toHaveBeenCalledWith('t-1')
  })
})
