import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import PersonProfileView from './PersonProfileView.vue'
import type { ParticipantNodeHit } from '../../features/people/participants/participants'

const hit: ParticipantNodeHit = {
  id: 'm2',
  name: 'Tuscany',
  slug: 'tuscany',
  type: 'MEMORY',
  node: { id: 'm2', type: 'MEMORY' } as never,
  participant: { id: 'p1', displayName: 'Mira', linkedUserId: 'u2' },
}

describe('PersonProfileView', () => {
  it('renders accessible memories for a linked participant and emits selection', async () => {
    const w = mount(PersonProfileView, { props: { name: 'Mira', hits: [hit], covers: { m2: 'blob:m2' } } })
    expect(w.find('.person-profile-title').text()).toBe('Mira')
    expect(w.find('.memory-name').text()).toBe('Tuscany')
    expect(w.find('.memory-cover').attributes('src')).toBe('blob:m2')
    await w.find('.person-memory-card').trigger('click')
    expect(w.emitted('openMemory')?.[0][0]).toEqual(hit)
  })
})
