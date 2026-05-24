import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import type { Memory, MemoryHeader } from '../api'
import MemoryDetail from './MemoryDetail.vue'

describe('MemoryDetail', () => {
  it('renders header date total media and chapters', () => {
    const wrapper = mount(MemoryDetail, {
      props: {
        tenantName: 'tenant',
        memory: memory(),
        header: header({
          directMediaCount: 12,
          descendantMediaCount: 22,
          childMemoryCount: 3
        }),
        childMemories: [],
        media: []
      },
      global: { stubs: detailStubs() }
    })

    expect(wrapper.text()).toContain('03.05.2026 - 05.05.2026')
    expect(wrapper.text()).toContain('34 Medien')
    expect(wrapper.text()).toContain('3 Kapitel')
    expect(wrapper.text()).not.toContain('davon')
    expect(wrapper.text()).not.toContain('Unter-Memories')
  })

  it('does not render scope metadata', () => {
    const wrapper = mount(MemoryDetail, {
      props: {
        tenantName: '',
        memory: memory(),
        header: header({
          scope: null,
          directMediaCount: 5,
          descendantMediaCount: 0
        }),
        childMemories: [],
        media: []
      },
      global: { stubs: detailStubs() }
    })

    expect(wrapper.text()).toContain('5 Medien')
    expect(wrapper.text()).not.toContain('Tenant')
  })

  it('hides chapter count when there are no chapters', () => {
    const wrapper = mount(MemoryDetail, {
      props: {
        tenantName: 'tenant',
        memory: memory(),
        header: header({
          directMediaCount: 5,
          descendantMediaCount: 0,
          childMemoryCount: 0
        }),
        childMemories: [],
        media: []
      },
      global: { stubs: detailStubs() }
    })

    expect(wrapper.text()).not.toContain('0 Kapitel')
  })
})

function detailStubs() {
  return {
    Button: { template: '<button><slot />{{ label }}</button>', props: ['label'] },
    Tabs: { template: '<section><slot /></section>' },
    TabList: { template: '<div><slot /></div>' },
    Tab: { template: '<button><slot /></button>' },
    TabPanels: { template: '<div><slot /></div>' },
    TabPanel: { template: '<div><slot /></div>' },
    MediaGrid: { template: '<div />' },
    MemoryCard: { template: '<article />' },
    MemoryCoverImage: { template: '<img :alt="alt" />', props: ['alt'] },
    DeleteMemoryDialog: { template: '<div />' },
    EditMemoryDialog: { template: '<div />' },
    MemoryCoverDialog: { template: '<div />' },
    MemoryShareDialog: { template: '<div />' },
    MemoryUploadDialog: { template: '<div />' }
  }
}

function memory(overrides: Partial<Memory> = {}): Memory {
  return {
    id: 'mem-1',
    parentId: null,
    name: 'trip',
    displayName: 'Trip',
    path: '/trip',
    coverMediaId: null,
    coverUrl: null,
    coverCrop: null,
    startDate: '2026-05-03',
    endDate: '2026-05-05',
    childCount: 0,
    mediaCount: 0,
    scope: { type: 'TENANT' },
    ...overrides
  }
}

function header(overrides: Partial<MemoryHeader> = {}): MemoryHeader {
  return {
    id: 'mem-1',
    displayName: 'Trip',
    path: '/trip',
    coverUrl: null,
    coverCrop: null,
    startDate: '2026-05-03',
    endDate: '2026-05-05',
    directMediaCount: 0,
    descendantMediaCount: 0,
    childMemoryCount: 0,
    scope: { type: 'TENANT' },
    ...overrides
  }
}
