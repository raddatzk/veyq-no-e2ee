// @vitest-environment happy-dom
import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import MemoryCoverDialog from './MemoryCoverDialog.vue'
import type { CoverPickItem, MemoryCover } from '../memoryCover'

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  messages: {
    en: {
      common: { cancel: 'Cancel' },
      memory: {
        zoom: 'Zoom',
        cover: { choose: 'Choose a cover', copy: 'copy', empty: 'empty', gallery: 'gallery', remove: 'Remove cover', save: 'Save cover' },
      },
    },
  },
})

const items: CoverPickItem[] = [
  { mediaId: 'm1', url: 'blob:1' },
  { mediaId: 'm2', url: 'blob:2' },
]

// Stub the PrimeVue Dialog/Button shells (happy-dom can't drive the overlay) — the crop math + emits are under test.
const stubs = {
  Dialog: { template: '<div class="dlg"><slot /></div>' },
  Button: {
    props: ['type', 'label', 'disabled'],
    template: `<button :type="type || 'button'" :disabled="disabled" @click="$emit('click', $event)">{{ label }}</button>`,
  },
}

async function mountDialog(cover: MemoryCover | null) {
  return mount(MemoryCoverDialog, { props: { items, cover }, global: { plugins: [i18n], stubs } })
}

/** The dialog's action buttons keyed by their label (the stub renders the label as text). */
const btn = (w: ReturnType<typeof mount>, label: string) => w.findAll('button').filter((b) => b.text() === label)[0]

/** Stub the cropper image's decoded natural size and fire its @load so the crop math runs (happy-dom decodes nothing). */
async function loadCropper(w: ReturnType<typeof mount>, natW: number, natH: number) {
  const img = w.find('.memory-cover-cropper img')
  Object.defineProperty(img.element, 'naturalWidth', { configurable: true, value: natW })
  Object.defineProperty(img.element, 'naturalHeight', { configurable: true, value: natH })
  await img.trigger('load')
  await w.vm.$nextTick()
}

const near = (a: number, b: number) => Math.abs(a - b) < 1e-9

describe('MemoryCoverDialog', () => {
  it('picking a media + saving emits a normalized cover (crop within 0..1)', async () => {
    const w = await mountDialog(null)
    // no cropper until a media is picked
    expect(w.find('.memory-cover-cropper').exists()).toBe(false)

    await w.findAll('.memory-cover-option')[0].trigger('click')
    await loadCropper(w, 400, 300) // a 4:3 source, centered full-frame at zoom 1

    await btn(w, 'Save cover').trigger('click')
    const cover = w.emitted('save')?.[0]?.[0] as MemoryCover
    expect(cover.mediaId).toBe('m1')
    // a 16:9 crop of a 4:3 image fills the width and takes 3/4 of the height, vertically centered
    expect(near(cover.crop.x, 0)).toBe(true)
    expect(near(cover.crop.width, 1)).toBe(true)
    expect(near(cover.crop.height, 0.75)).toBe(true)
    expect(near(cover.crop.y, 0.125)).toBe(true)
    expect(w.emitted('close')).toBeTruthy()
  })

  it('remove emits null', async () => {
    const cover: MemoryCover = { mediaId: 'm1', crop: { x: 0.1, y: 0.2, width: 0.5, height: 0.5 } }
    const w = await mountDialog(cover)
    await btn(w, 'Remove cover').trigger('click')
    expect(w.emitted('save')?.[0]?.[0]).toBeNull()
    expect(w.emitted('close')).toBeTruthy()
  })

  it('a preset crop round-trips through open → save', async () => {
    const crop = { x: 0.1, y: 0.2, width: 0.5, height: 0.5 } // achievable 16:9 region of a 3200×1800 source
    const w = await mountDialog({ mediaId: 'm1', crop })
    // the current cover's media is preselected → the cropper is already shown
    expect(w.find('.memory-cover-cropper').exists()).toBe(true)
    await loadCropper(w, 3200, 1800)

    await btn(w, 'Save cover').trigger('click')
    const saved = w.emitted('save')?.[0]?.[0] as MemoryCover
    expect(saved.mediaId).toBe('m1')
    expect(near(saved.crop.x, crop.x)).toBe(true)
    expect(near(saved.crop.y, crop.y)).toBe(true)
    expect(near(saved.crop.width, crop.width)).toBe(true)
    expect(near(saved.crop.height, crop.height)).toBe(true)
  })
})
