// @vitest-environment happy-dom
import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import MemoryCard from './MemoryCard.vue'
import { NodeKind } from '../../../api/generated/content'
import type { TreeNode } from '../tree'

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  messages: { en: { memory: { favorite: 'Favorite', unfavorite: 'Remove favorite' } } },
})

const memory: TreeNode = { id: 'm1', kind: NodeKind.Memory, name: 'Rom', parentId: null, orderIndex: 0, wrappedContentKey: null }

const mountCard = (favorite: boolean) =>
  mount(MemoryCard, { props: { memory, favorite }, global: { plugins: [i18n] } })

describe('MemoryCard favorite pin', () => {
  it('shows an outline heart when not favorited', () => {
    const w = mountCard(false)
    expect(w.find('.memory-card-pin').exists()).toBe(true)
    expect(w.find('.memory-card-pin.is-favorite').exists()).toBe(false)
    expect(w.find('.memory-card-pin .pi-heart').exists()).toBe(true)
  })

  it('shows a filled accent heart when favorited', () => {
    const w = mountCard(true)
    expect(w.find('.memory-card-pin.is-favorite').exists()).toBe(true)
    expect(w.find('.memory-card-pin .pi-heart-fill').exists()).toBe(true)
  })

  it('toggles favorite on pin click WITHOUT opening the Memory', async () => {
    const w = mountCard(false)
    await w.find('.memory-card-pin').trigger('click')
    expect(w.emitted('toggleFavorite')?.[0]?.[0]).toMatchObject({ id: 'm1' })
    expect(w.emitted('open')).toBeFalsy()
  })

  it('opens the Memory on the card body click', async () => {
    const w = mountCard(false)
    await w.find('.memory-card-main').trigger('click')
    expect(w.emitted('open')?.[0]?.[0]).toMatchObject({ id: 'm1' })
  })
})
