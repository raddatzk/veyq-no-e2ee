import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
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
  beforeEach(() => {
    vi.clearAllMocks()
  })

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

  it('shows selected count and selected candidate state', async () => {
    vi.mocked(listMemoryParticipantCandidates).mockResolvedValue([
      participant({ userId: 1, displayName: 'Alex' }),
      participant({ userId: 2, displayName: 'Florian' })
    ])

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

    expect(wrapper.text()).toContain('Wähle, wer in dieser Memory vorkommt.')
    expect(wrapper.text()).toContain('1 ausgewählt')
    expect(wrapper.findAll('.participants-candidate-selected')).toHaveLength(1)

    await wrapper.findAll('input[type="checkbox"]')[1].setValue(true)

    expect(wrapper.text()).toContain('2 ausgewählt')
    expect(wrapper.findAll('.participants-candidate-selected')).toHaveLength(2)
  })

  it('filters participants by display name', async () => {
    vi.mocked(listMemoryParticipantCandidates).mockResolvedValue([
      participant({ userId: 1, displayName: 'Alex' }),
      participant({ userId: 2, displayName: 'Florian' })
    ])

    const wrapper = mountDialog()
    await flushPromises()

    await wrapper.find('input[type="search"]').setValue('flo')

    expect(wrapper.text()).not.toContain('Alex')
    expect(wrapper.text()).toContain('Florian')
  })

  it('filters participants by permalink', async () => {
    vi.mocked(listMemoryParticipantCandidates).mockResolvedValue([
      participant({ userId: 1, displayName: 'Alex', permalink: 'alex' }),
      participant({ userId: 2, displayName: 'Mira', permalink: 'summer-mira' })
    ])

    const wrapper = mountDialog()
    await flushPromises()

    await wrapper.find('input[type="search"]').setValue('summer')

    expect(wrapper.text()).not.toContain('Alex')
    expect(wrapper.text()).toContain('Mira')
  })

  it('keeps hidden selections while filtering', async () => {
    vi.mocked(listMemoryParticipantCandidates).mockResolvedValue([
      participant({ userId: 1, displayName: 'Alex' }),
      participant({ userId: 2, displayName: 'Florian' })
    ])

    const wrapper = mountDialog({
      participants: [participant({ userId: 1, displayName: 'Alex' })]
    })
    await flushPromises()

    expect(wrapper.text()).toContain('1 ausgewählt')

    await wrapper.find('input[type="search"]').setValue('flo')

    expect(wrapper.text()).toContain('1 ausgewählt')
    expect(wrapper.findAll('.participants-candidate-selected')).toHaveLength(0)

    await wrapper.find('input[type="checkbox"]').setValue(true)

    expect(wrapper.text()).toContain('2 ausgewählt')
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

function mountDialog(props: Partial<InstanceType<typeof MemoryParticipantsDialog>['$props']> = {}) {
  return mount(MemoryParticipantsDialog, {
    props: {
      visible: true,
      tenantName: 'family',
      memoryId: 'mem-1',
      participants: [],
      ...props
    },
    global: { stubs: primeStubs() }
  })
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
    InputText: {
      props: ['modelValue', 'type', 'autocomplete', 'placeholder', 'ariaLabel'],
      emits: ['update:modelValue'],
      template: '<input :type="type || `text`" :autocomplete="autocomplete" :placeholder="placeholder" :aria-label="ariaLabel" :value="modelValue" @input="$emit(`update:modelValue`, $event.target.value)" />'
    },
    Message: true,
    ProgressSpinner: true
  }
}
