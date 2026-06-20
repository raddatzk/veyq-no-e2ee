import { mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it } from 'vitest'
import HomeSidebar from './HomeSidebar.vue'
import { setUnlocked } from '../session'
import type { SpaceEntry } from '../../features/home/home'

const key = { suiteId: 'v1', kemPublicKey: '' }
const spaces: SpaceEntry[] = [
  { kind: 'TENANT', displayName: 'Owner Tenant', role: 'Owner', tenantId: 't1', domain: 'owner.test', scopeId: 'tenant-owner', scopePublicKey: key },
  { kind: 'TENANT', displayName: 'Member Tenant', role: 'Member', tenantId: 't2', domain: 'member.test', scopeId: 'tenant-member', scopePublicKey: key },
  { kind: 'PERSONAL', displayName: 'Kevin', scopeId: 'personal', scopePublicKey: key },
  { kind: 'GROUP', displayName: 'Owner Group', role: 'GroupOwner', tenantId: 't1', scopeId: 'group-owner', scopePublicKey: key },
  { kind: 'GROUP', displayName: 'Member Group', role: 'GroupMember', tenantId: 't1', scopeId: 'group-member', scopePublicKey: key },
]

beforeEach(() => {
  setUnlocked(null)
})

describe('HomeSidebar', () => {
  it('shows tenant creation only to server owners', () => {
    setUnlocked({ sessionToken: 't', user: { id: 'u', email: 'k@example.com', displayName: 'Kevin', serverRole: 'ServerMember' } } as never)
    const member = mount(HomeSidebar, { props: { spaces, currentUserName: 'Kevin' } })
    expect(member.find('button[aria-label="Neuer Tenant"]').exists()).toBe(false)

    setUnlocked({ sessionToken: 't', user: { id: 'u', email: 'k@example.com', displayName: 'Kevin', serverRole: 'ServerOwner' } } as never)
    const owner = mount(HomeSidebar, { props: { spaces, currentUserName: 'Kevin' } })
    expect(owner.find('button[aria-label="Neuer Tenant"]').exists()).toBe(true)
  })

  it('shows management gear only for owned tenant and group rows', () => {
    setUnlocked({ sessionToken: 't', user: { id: 'u', email: 'k@example.com', displayName: 'Kevin', serverRole: 'ServerOwner' } } as never)
    const wrapper = mount(HomeSidebar, { props: { spaces, currentUserName: 'Kevin' } })

    expect(wrapper.findAll('.space-row-wrap').filter((row) => row.text().includes('Owner Tenant') && row.find('.space-invite').exists())).toHaveLength(1)
    expect(wrapper.findAll('.space-row-wrap').find((row) => row.text().includes('Owner Tenant'))?.find('.space-invite').attributes('aria-label')).toBe('Tenant verwalten')
    expect(wrapper.findAll('.space-row-wrap').filter((row) => row.text().includes('Member Tenant') && row.find('.space-invite').exists())).toHaveLength(0)
    expect(wrapper.findAll('.space-row-wrap').filter((row) => row.text().includes('Owner Group') && row.find('.space-invite').exists())).toHaveLength(1)
    expect(wrapper.findAll('.space-row-wrap').filter((row) => row.text().includes('Member Group') && row.find('.space-invite').exists())).toHaveLength(0)
  })
})
