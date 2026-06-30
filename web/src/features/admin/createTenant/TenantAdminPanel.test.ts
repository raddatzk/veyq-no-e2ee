// @vitest-environment happy-dom
// uc.admin.create-tenant (web Slice A) — the "Manage tenants" rubric of the Server Settings page: it loads +
// lists tenants, renders a PENDING tenant with edit + delete actions, reloads on a child `created` event, and
// confirm-gates delete (revoke). A 403 surfaces a "not authorized" state and no list. Component-level acceptance
// for `create-pending-tenant` + `list-tenants`.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'

const push = vi.fn()
vi.mock('vue-router', () => ({ useRouter: () => ({ push }) }))

vi.mock('./tenants', () => ({
  listTenants: vi.fn(),
  resendInvite: vi.fn().mockResolvedValue({}),
  reassignInvite: vi.fn().mockResolvedValue({}),
  revokeTenant: vi.fn().mockResolvedValue(undefined),
  tenantError: vi.fn().mockResolvedValue({ status: 0, code: null }),
}))

import TenantAdminPanel from './TenantAdminPanel.vue'
import { listTenants, revokeTenant } from './tenants'

const i18n = createI18n({ legacy: false, locale: 'en', missingWarn: false, fallbackWarn: false, messages: { en: {} } })

// A stub that lets a test fire the dialog's `created` event without driving the real dialog form.
const CreateTenantDialogStub = {
  props: ['visible'],
  emits: ['created', 'update:visible'],
  template: '<button class="dlg-created" @click="$emit(\'created\')">created</button>',
}

const stubs = {
  CreateTenantDialog: CreateTenantDialogStub,
  Dialog: { props: ['visible'], template: '<div v-if="visible"><slot /></div>' },
  InputText: { props: ['modelValue'], template: '<input :value="modelValue" />' },
  Button: {
    props: ['label', 'icon', 'ariaLabel'],
    template: '<button :aria-label="ariaLabel" @click="$emit(\'click\', $event)">{{ label }}</button>',
  },
  Message: { template: '<div class="msg"><slot /></div>' },
}

const PENDING = {
  tenantId: 't-1', name: 'Acme', domain: 'acme.example', ownerEmail: 'owner@acme.example', status: 'PENDING',
}
const ACTIVE = {
  tenantId: 't-2', name: 'Live', domain: 'live.example', ownerEmail: 'o@live.example', status: 'ACTIVE',
}

function mountPanel() {
  return mount(TenantAdminPanel, { global: { plugins: [i18n], stubs } })
}

describe('TenantAdminPanel (uc.admin.create-tenant, web)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(listTenants).mockResolvedValue([])
  })

  it('lists nothing on an empty instance', async () => {
    const wrapper = mountPanel()
    await flushPromises()
    expect(wrapper.find('.tenant-admin-row').exists()).toBe(false)
    expect(listTenants).toHaveBeenCalledTimes(1)
  })

  it('reloads on `created` and renders the new tenant as a PENDING row with edit + delete', async () => {
    const wrapper = mountPanel()
    await flushPromises() // initial empty load

    vi.mocked(listTenants).mockResolvedValue([PENDING as never])
    await wrapper.find('.dlg-created').trigger('click')
    await flushPromises()

    const row = wrapper.find('.tenant-admin-row')
    expect(row.exists()).toBe(true)
    expect(row.text()).toContain('Acme')
    expect(row.find('.veyq-badge.warning').exists()).toBe(true) // PENDING
    expect(listTenants).toHaveBeenCalledTimes(2)
    // a PENDING tenant exposes exactly the two row actions: edit + delete
    expect(row.findAll('.management-actions button')).toHaveLength(2)
  })

  it('an ACTIVE tenant shows the status badge but no row actions', async () => {
    vi.mocked(listTenants).mockResolvedValue([ACTIVE as never])
    const wrapper = mountPanel()
    await flushPromises()
    const row = wrapper.find('.tenant-admin-row')
    expect(row.find('.veyq-badge.success').exists()).toBe(true)
    expect(row.find('.management-actions').exists()).toBe(false)
  })

  it('delete is confirm-gated and delegates revoke to the facade', async () => {
    vi.mocked(listTenants).mockResolvedValue([PENDING as never])
    const wrapper = mountPanel()
    await flushPromises()

    window.confirm = () => false
    await wrapper.findAll('.management-actions button')[1].trigger('click') // delete (cancelled)
    await flushPromises()
    expect(revokeTenant).not.toHaveBeenCalled()

    window.confirm = () => true
    await wrapper.findAll('.management-actions button')[1].trigger('click') // delete (confirmed)
    await flushPromises()
    expect(revokeTenant).toHaveBeenCalledWith('t-1')
  })

  it('shows a not-authorized state when the list call is forbidden (403)', async () => {
    const { tenantError } = await import('./tenants')
    vi.mocked(listTenants).mockRejectedValueOnce(new Error('403'))
    vi.mocked(tenantError).mockResolvedValueOnce({ status: 403, code: null })
    const wrapper = mountPanel()
    await flushPromises()
    expect(wrapper.find('.tenant-admin-row').exists()).toBe(false)
    expect(wrapper.find('.msg').exists()).toBe(true)
  })

  it('redirects to login when the list call is unauthenticated (401)', async () => {
    const { tenantError } = await import('./tenants')
    vi.mocked(listTenants).mockRejectedValueOnce(new Error('401'))
    vi.mocked(tenantError).mockResolvedValueOnce({ status: 401, code: null })
    mountPanel()
    await flushPromises()
    expect(push).toHaveBeenCalledWith({ name: 'login' })
  })
})
