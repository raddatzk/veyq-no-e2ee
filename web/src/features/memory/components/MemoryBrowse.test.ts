// @vitest-environment happy-dom
import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import MemoryBrowse from './MemoryBrowse.vue'
import { NodeKind } from '../../../api/generated/content'
import type { TreeNode } from '../tree'

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  messages: {
    en: { memory: { memories: 'Memories', folders: 'Folders', memoryEmpty: 'No Memories', folderEmpty: 'No folders' } },
  },
})

const node = (id: string, kind: NodeKind, name: string): TreeNode => ({
  id, kind, name, parentId: null, orderIndex: 0, wrappedContentKey: null, wrappedMeta: null,
})

describe('MemoryBrowse', () => {
  it('renders the level (decrypted folder + Memory names) and emits drill/open on click', async () => {
    const folders = [node('f1', NodeKind.Folder, 'Trips')]
    const memories = [node('m1', NodeKind.Memory, 'Solo')]
    const wrapper = mount(MemoryBrowse, { props: { folders, memories }, global: { plugins: [i18n] } })

    expect(wrapper.text()).toContain('Solo')
    expect(wrapper.text()).toContain('Trips')

    await wrapper.findAll('button').find((b) => b.text().includes('Solo'))!.trigger('click')
    expect(wrapper.emitted('openMemory')?.[0]?.[0]).toMatchObject({ id: 'm1' })

    await wrapper.findAll('button').find((b) => b.text().includes('Trips'))!.trigger('click')
    expect(wrapper.emitted('openFolder')?.[0]?.[0]).toMatchObject({ id: 'f1' })
  })

  it('shows the empty states when a level has no folders/Memories', () => {
    const wrapper = mount(MemoryBrowse, { props: { folders: [], memories: [] }, global: { plugins: [i18n] } })
    expect(wrapper.text()).toContain('No Memories')
    expect(wrapper.text()).toContain('No folders')
  })
})
