import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import MemoryParticipantsDialog from './MemoryParticipantsDialog.vue'
import {
  addMemoryParticipant,
  listMemoryParticipantCandidates,
  removeMemoryParticipant,
  type MemoryParticipantSummary
} from '../api'

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return {
    ...actual,
    addMemoryParticipant: vi.fn(),
    listMemoryParticipantCandidates: vi.fn(),
    removeMemoryParticipant: vi.fn()
  }
})

describe('MemoryParticipantsDialog', () => {
  it('loads candidates and saves add remove diffs', async () => {
    vi.mocked(listMemoryParticipantCandidates).mockResolvedValue([
      participant({ userId: 1, displayName: 'Alex' }),
      participant({ userId: 2, displayName: 'Florian' })
    ])
    vi.mocked(addMemoryParticipant).mockResolvedValue(participant({ userId: 2, displayName: 'Florian' }))
    vi.mocked(removeMemoryParticipant).mockResolvedValue(undefined)

    const wrapper = mount(MemoryParticipantsDialog, {
      props: {
        visible: true,
        tenantName: 'family',
        memoryId: 'mem-1',
        participants: [participant({ userId: 1, displayName: 'Alex' })]
      },
      global: { stubs: primeStubs() }
    })
    await flushPromises()

    const checkboxes = wrapper.findAll('input[type="checkbox"]')
    await checkboxes[0].setValue(false)
    await checkboxes[1].setValue(true)
    await buttonByText(wrapper, 'Speichern').trigger('click')
    await flushPromises()

    expect(removeMemoryParticipant).toHaveBeenCalledWith('family', 'mem-1', 1)
    expect(addMemoryParticipant).toHaveBeenCalledWith('family', 'mem-1', 2)
    expect(wrapper.emitted('saved')).toBeTruthy()
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

function buttonByText(wrapper: ReturnType<typeof mount>, text: string) {
  const button = wrapper.findAll('button').find((candidate) => candidate.text().includes(text))
  if (!button) throw new Error(`Button not found: ${text}`)
  return button
}

function primeStubs() {
  return {
    Button: {
      props: ['label'],
      emits: ['click'],
      template: '<button v-bind="$attrs" type="button" @click="$emit(\'click\', $event)">{{ label }}<slot /></button>'
    },
    Dialog: {
      props: ['visible'],
      emits: ['update:visible'],
      template: '<div><slot /><slot name="footer" /></div>'
    },
    Message: true,
    ProgressSpinner: true
  }
}
