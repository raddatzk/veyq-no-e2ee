import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import JournalEmbedPickerDialog from './JournalEmbedPickerDialog.vue'

// The picker loads candidates through the listing modules and reads the unlocked session; stub both so the
// dialog renders deterministically and we can drive selection / caption / edit-vs-insert behaviour.
vi.mock('../../../app/session', () => ({
  session: { apiBase: '', unlocked: {} },
}))
vi.mock('../../../features/gallery/gallery', () => ({
  loadGalleryTiles: vi.fn(async () => [
    { id: 'm1', url: 'blob:m1' },
    { id: 'm2', url: 'blob:m2' },
  ]),
}))
vi.mock('../../../features/memory/track/track', () => ({
  getTracks: vi.fn(async () => [{ id: 't1', name: 'Hike' }, { id: 't2', name: 'Ride' }]),
}))
vi.mock('../../../features/memory/playlist/playlist', () => ({
  listPlaylists: vi.fn(async () => [{ id: 'p1', title: 'Mix' }]),
  PROVIDER_LABEL: { SPOTIFY: 'Spotify' },
}))
vi.mock('../../../features/memory/browse/browse', () => ({
  listChildren: vi.fn(async () => [
    { id: 'ch1', type: 'CHAPTER' },
    { id: 'ch2', type: 'CHAPTER' },
    { id: 'f1', type: 'FOLDER' },
  ]),
  decodeNode: (_key: unknown, n: { id: string }) => ({ name: `Chapter ${n.id}`, slug: n.id }),
}))

// PrimeVue Dialog teleports its body; render slots inline (incl. the #footer) so queries stay on the wrapper.
const stubs = {
  Dialog: { template: '<div class="dialog"><slot /><div class="dialog-footer"><slot name="footer" /></div></div>' },
  Button: { props: ['label', 'disabled'], template: '<button :disabled="disabled" @click="$emit(\'click\')">{{ label }}</button>' },
  InputText: { props: ['modelValue'], template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />' },
  ProgressSpinner: true,
}

const active = { scope: { scopeId: 's1' }, contentKey: new Uint8Array(), entry: {} } as never

async function mountPicker(props: Record<string, unknown>) {
  const w = mount(JournalEmbedPickerDialog, { global: { stubs }, props: { active, nodeId: 'n1', ...props } as never })
  await flush()
  return w
}

// Let onMounted's async candidate load settle.
const flush = () => new Promise((r) => setTimeout(r, 0))

describe('JournalEmbedPickerDialog', () => {
  it('toggles multiple media ids on (multi-select) and confirms them with a caption', async () => {
    const w = await mountPicker({ kind: 'media' })
    const thumbs = w.findAll('.picker-thumb')
    expect(thumbs).toHaveLength(2)

    await thumbs[0].trigger('click')
    await thumbs[1].trigger('click')
    expect(w.findAll('.picker-thumb.selected')).toHaveLength(2)

    await w.find('.picker-label input').setValue('Beach day')
    await w.findAll('button').find((b) => b.text() === 'Einfügen')!.trigger('click')

    const payload = w.emitted('select')?.[0]?.[0] as { ids: string[]; label?: string }
    expect(payload.ids).toEqual(['m1', 'm2'])
    expect(payload.label).toBe('Beach day')
  })

  it('enforces single selection for a chapter embed (a second pick replaces the first)', async () => {
    const w = await mountPicker({ kind: 'chapter' })
    const rows = w.findAll('.picker-row')
    expect(rows).toHaveLength(2) // only the two CHAPTER nodes, not the folder

    await rows[0].trigger('click')
    await rows[1].trigger('click')
    expect(w.findAll('.picker-row.selected')).toHaveLength(1)

    await w.findAll('button').find((b) => b.text() === 'Einfügen')!.trigger('click')
    expect((w.emitted('select')?.[0]?.[0] as { ids: string[] }).ids).toEqual(['ch2'])
  })

  it('disables Insert until at least one id is selected', async () => {
    const w = await mountPicker({ kind: 'track' })
    const insert = w.findAll('button').find((b) => b.text() === 'Einfügen')!
    expect((insert.element as HTMLButtonElement).disabled).toBe(true)

    await w.findAll('.picker-row')[0].trigger('click')
    expect((insert.element as HTMLButtonElement).disabled).toBe(false)
  })

  it('offers Delete only while editing an existing embed', async () => {
    const inserting = await mountPicker({ kind: 'media' })
    expect(inserting.findAll('button').some((b) => b.text() === 'Löschen')).toBe(false)

    const editing = await mountPicker({ kind: 'media', initial: { ids: ['m1'], label: 'Old caption' } })
    const del = editing.findAll('button').find((b) => b.text() === 'Löschen')
    expect(del).toBeTruthy()
    // pre-selects the initial id and caption
    expect(editing.findAll('.picker-thumb.selected')).toHaveLength(1)
    expect((editing.find('.picker-label input').element as HTMLInputElement).value).toBe('Old caption')

    await del!.trigger('click')
    expect(editing.emitted('remove')).toBeTruthy()
  })
})
