import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

// TenantView is the web "home + Memory" view: a space breadcrumb, folder/memory grids that navigate, and —
// inside a Memory — the tab bar over the gallery / journal / map / etc. We mock the data layer + session and
// stub the heavy tab components so the test asserts the view's own rendering + folder navigation behaviour.
const push = vi.hoisted(() => vi.fn())
vi.mock('vue-router', () => ({ useRouter: () => ({ push }) }))

const loadLevel = vi.hoisted(() => vi.fn())
const loadCover = vi.hoisted(() => vi.fn(async () => null))
vi.mock('../features/home/home', async (orig) => {
  const actual = await orig<typeof import('../features/home/home')>()
  return { ...actual, loadLevel, loadCover, moveDestinations: vi.fn(async () => []) }
})
vi.mock('../features/memory/favorite/favorite', () => ({
  listFavoriteNodeIds: vi.fn(async () => []),
  setMemoryFavorite: vi.fn(async () => true),
}))
vi.mock('../app/session', () => ({ session: { apiBase: '', unlocked: {} } }))

import TenantView from './TenantView.vue'
import type { ActiveSpace, LevelEntry } from '../features/home/home'

const folderNode = (id: string): LevelEntry => ({ node: { id, type: 'FOLDER' } as never, name: `Folder ${id}`, slug: `folder-${id}` })
const memoryNode = (id: string): LevelEntry => ({ node: { id, type: 'MEMORY' } as never, name: `Memory ${id}`, slug: `memory-${id}` })

const active = {
  entry: { kind: 'TENANT', displayName: 'Acme', tenantId: 't', domain: 'acme.veyq.app', scopeId: 's1', scopePublicKey: {} },
  scope: { scopeId: 's1' },
  contentKey: new Uint8Array(),
} as unknown as ActiveSpace

// Stub the heavy tab components + PrimeVue widgets so we observe TenantView's own structure.
const stubs = {
  MemoryGallery: { template: '<div class="gallery-stub" />' },
  MemoryJournal: { template: '<div class="journal-stub" />' },
  MemoryMap: { template: '<div class="map-stub" />' },
  MemoryParticipants: { template: '<div class="participants-stub" />' },
  MemoryPlaylists: { template: '<div class="playlists-stub" />' },
  MemoryShare: { template: '<div class="share-stub" />' },
  Button: { props: ['label'], template: '<button>{{ label }}</button>' },
  Dialog: { template: '<div><slot /></div>' },
  InputText: true,
  Message: { template: '<div><slot /></div>' },
  ProgressSpinner: true,
}

function mountView(props: { folders: LevelEntry[]; memory: LevelEntry | null }) {
  return mount(TenantView, { global: { stubs }, props: { active, ...props } })
}

describe('TenantView (home + Memory views)', () => {
  beforeEach(() => {
    push.mockReset()
    loadLevel.mockReset()
  })

  it('renders the folder and memory grids for the space root', async () => {
    loadLevel.mockResolvedValue([folderNode('f1'), memoryNode('m1'), memoryNode('m2')])
    const w = mountView({ folders: [], memory: null })
    await flushPromises()

    const cards = w.findAll('.node-card')
    expect(cards.length).toBe(3)
    expect(w.text()).toContain('Folder f1')
    expect(w.text()).toContain('Memory m1')
    // the space root breadcrumb shows the space display name
    expect(w.find('.memory-breadcrumb').text()).toContain('Acme')
  })

  it('navigates into a folder when its card is clicked', async () => {
    loadLevel.mockResolvedValue([folderNode('f1')])
    const w = mountView({ folders: [], memory: null })
    await flushPromises()

    await w.find('.node-folder').trigger('click')
    expect(push).toHaveBeenCalledWith('/folder-f1')
  })

  it('renders the Memory tabs with the gallery active and switches to the journal', async () => {
    loadLevel.mockResolvedValue([]) // no chapters under this memory
    const w = mountView({ folders: [], memory: memoryNode('mem') })
    await flushPromises()

    // tab bar present; gallery shows first
    const tabs = w.findAll('.memory-tab')
    expect(tabs.length).toBe(6)
    expect(w.find('.gallery-stub').exists()).toBe(true)
    expect(w.find('.journal-stub').exists()).toBe(false)

    // switch to the journal tab → the embed-aware journal renders, gallery unmounts
    await tabs[1].trigger('click')
    expect(w.find('.journal-stub').exists()).toBe(true)
    expect(w.find('.gallery-stub').exists()).toBe(false)
  })
})
