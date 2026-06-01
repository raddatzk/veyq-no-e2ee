import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import type { Memory } from '../api'
import MemoryCard from './MemoryCard.vue'

describe('MemoryCard', () => {
  it('renders a single date when the memory range is within one day', () => {
    const wrapper = mount(MemoryCard, {
      props: {
        memory: memory({
          startDate: '2026-05-03',
          endDate: '2026-05-03'
        })
      },
      global: { stubs: cardStubs() }
    })

    expect(wrapper.text()).toContain('03.05.2026')
  })

  it('renders a date range across multiple days', () => {
    const wrapper = mount(MemoryCard, {
      props: {
        memory: memory({
          startDate: '2026-05-03',
          endDate: '2026-05-05'
        })
      },
      global: { stubs: cardStubs() }
    })

    expect(wrapper.text()).toContain('03.05.2026 - 05.05.2026')
  })

  it('renders the cover image when a cover url exists', () => {
    const wrapper = mount(MemoryCard, {
      props: {
        memory: memory({
          coverUrl: '/api/tenant/memories/mem-1/cover/content'
        })
      },
      global: { stubs: cardStubs() }
    })

    expect(wrapper.find('.memory-card-cover img').exists()).toBe(true)
    expect(wrapper.find('.memory-card-icon').exists()).toBe(false)
  })

  it('renders the fallback cover icon without additional card metadata', () => {
    const wrapper = mount(MemoryCard, {
      props: {
        memory: memory({
          mediaCount: 99
        })
      },
      global: { stubs: cardStubs() }
    })

    expect(wrapper.find('.memory-card-icon').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('99')
  })
})

function cardStubs() {
  return {
    Card: {
      template: '<article><slot name="content" /></article>'
    }
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
    favorite: false,
    scope: null,
    ...overrides
  }
}
