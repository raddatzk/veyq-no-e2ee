import { mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import MemoryParticipantsStrip from './MemoryParticipantsStrip.vue'
import type { MemoryParticipantSummary } from '../api'

class ResizeObserverMock {
  observe = vi.fn()
  disconnect = vi.fn()
}

describe('MemoryParticipantsStrip', () => {
  beforeEach(() => {
    vi.stubGlobal('ResizeObserver', ResizeObserverMock)
  })

  it('links visible participants to their profile routes', async () => {
    const wrapper = mount(MemoryParticipantsStrip, {
      props: {
        tenantName: 'family',
        participants: [participant({ displayName: 'Florian', permalink: 'florian' })]
      },
      global: { stubs: primeStubs() }
    })
    await wrapper.vm.$nextTick()

    const link = wrapper.find('a.memory-participant-chip')
    expect(link.text()).not.toContain('Florian')
    expect(link.attributes('aria-label')).toBe('Florian')
    expect(link.attributes('title')).toBe('Florian')
    expect(link.attributes('href')).toBe('/~florian')
  })

  it('renders avatar images when available', async () => {
    const avatarUrl = 'https://objects.example.com/veyq/profile-avatars/usr_alex/7e5f64dc?X-Amz-Expires=900&X-Amz-Signature=test'
    const wrapper = mount(MemoryParticipantsStrip, {
      props: {
        tenantName: 'family',
        participants: [participant({ displayName: 'Alex', avatarUrl })]
      },
      global: { stubs: primeStubs() }
    })
    await wrapper.vm.$nextTick()

    expect(wrapper.find('img.memory-participant-avatar-image').attributes('src')).toBe(avatarUrl)
  })

  it('moves additional participants behind the overflow control', async () => {
    const rect = vi
      .spyOn(HTMLElement.prototype, 'getBoundingClientRect')
      .mockReturnValue({ width: 80, height: 32, top: 0, left: 0, right: 80, bottom: 32 } as DOMRect)
    const wrapper = mount(MemoryParticipantsStrip, {
      props: {
        tenantName: 'family',
        participants: [
          participant({ userId: 1, displayName: 'Alex', permalink: 'alex' }),
          participant({ userId: 2, displayName: 'Florian', permalink: 'florian' }),
          participant({ userId: 3, displayName: 'Mira', permalink: 'mira' })
        ]
      },
      attachTo: document.body,
      global: { stubs: primeStubs() }
    })

    await wrapper.vm.$nextTick()

    expect(wrapper.findAll('.memory-participant-chip')).toHaveLength(1)
    expect(wrapper.find('.memory-participants-overflow').exists()).toBe(true)
    rect.mockRestore()
  })
})

function participant(overrides: Partial<MemoryParticipantSummary> = {}): MemoryParticipantSummary {
  return {
    userId: 1,
    displayName: 'Alex',
    permalink: 'alex',
    initials: 'A',
    ...overrides
  }
}

function primeStubs() {
  return {
    RouterLink: {
      props: ['to'],
      template: '<a :href="to"><slot /></a>'
    },
    Button: {
      props: ['label', 'icon'],
      emits: ['click'],
      template: '<button v-bind="$attrs" type="button" @click="$emit(\'click\', $event)">{{ label }}<slot /></button>'
    },
    Menu: true
  }
}
