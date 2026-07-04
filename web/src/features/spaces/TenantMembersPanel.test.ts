// @vitest-environment happy-dom
// uc.spaces.add-member (web, cr-0077) — the member directory rubric: it lists the active tenant's members with
// a granted/awaiting-access badge, and shows the invite button only to the OWNER (admission is owner-gated).
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import { createRouter, createMemoryHistory } from 'vue-router'

vi.mock('./members', () => ({
  listScopeMembers: vi.fn(),
  memberError: vi.fn().mockResolvedValue({ status: 0, code: null }),
}))
vi.mock('./scopes', () => ({ listMyTenants: vi.fn() }))

import TenantMembersPanel from './TenantMembersPanel.vue'
import { listScopeMembers } from './members'
import { listMyTenants } from './scopes'
import { activeSpace, clearActiveSpace } from '../../app/activeSpace'
import { ScopeKind } from '../../api/generated/scopes'

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  missingWarn: false,
  fallbackWarn: false,
  messages: {
    en: {
      members: {
        title: 'Members', sub: 'sub', invite: 'Invite member', loading: 'Loading…', empty: 'No members',
        loadFailed: 'Failed', notAuthorized: 'Not authorized', statusActive: 'Active', statusAwaiting: 'Awaiting access',
      },
    },
  },
})

const stubs = {
  Button: { props: ['label'], template: '<button>{{ label }}</button>' },
  Message: { template: '<div class="msg"><slot /></div>' },
  InviteMemberDialog: true,
}

const MEMBERS = [
  { userId: 'u-own', displayName: 'Owner', email: 'owner@x.z', role: 'OWNER', identityPublicKey: 'AA==', hasGrant: true },
  { userId: 'u-pend', displayName: 'Partner', email: 'partner@x.z', role: 'MEMBER', identityPublicKey: 'BB==', hasGrant: false },
]

async function mountPanel(role: 'OWNER' | 'MEMBER') {
  vi.mocked(listScopeMembers).mockResolvedValue(MEMBERS as never)
  vi.mocked(listMyTenants).mockResolvedValue([{ tenantId: 't-1', name: 'Fam', domain: '127.0.0.1', role }] as never)
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', name: 'login', component: { template: '<div/>' } }] })
  const wrapper = mount(TenantMembersPanel, { global: { plugins: [router, i18n], stubs } })
  await flushPromises()
  return wrapper
}

describe('TenantMembersPanel (uc.spaces.add-member, web)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    clearActiveSpace()
    activeSpace.kind = ScopeKind.Tenant
    activeSpace.scopeId = 't-1'
    activeSpace.tenantId = 't-1'
  })

  it('lists members with a granted vs awaiting-access badge', async () => {
    const wrapper = await mountPanel('OWNER')
    const rows = wrapper.findAll('.member-row')
    expect(rows).toHaveLength(2)
    expect(wrapper.text()).toContain('Owner')
    expect(wrapper.text()).toContain('Partner')
    // The pending member shows "Awaiting access"; the granted one shows "Active".
    const badges = wrapper.findAll('.veyq-badge').map((b) => b.text())
    expect(badges).toContain('Active')
    expect(badges).toContain('Awaiting access')
    expect(wrapper.find('.veyq-badge.warning').text()).toBe('Awaiting access')
  })

  it('shows the invite button to the owner', async () => {
    const wrapper = await mountPanel('OWNER')
    expect(wrapper.text()).toContain('Invite member')
  })

  it('hides the invite button from a non-owner member (admission is owner-only)', async () => {
    const wrapper = await mountPanel('MEMBER')
    expect(wrapper.text()).not.toContain('Invite member')
  })
})
