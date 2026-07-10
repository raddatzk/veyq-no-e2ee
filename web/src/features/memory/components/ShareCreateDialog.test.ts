// @vitest-environment happy-dom
// cr-0116: the share CREATE dialog's role switcher offers only VIEWER + CONTRIBUTOR — the uploader (drop-box) is a
// SEPARATE memory action, never a switcher option (the uploader action itself is capability-gated in MemoryDetail,
// and the server rejects createShare(UPLOADER) with 422 when the capability is off).
import { describe, it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'

// The dialog decrypts an editShare on mount; stub the share data layer so it mounts crypto/network-free.
vi.mock('../share', () => ({
  createShare: vi.fn(),
  updateShare: vi.fn(),
  readShareName: vi.fn(() => ''),
  readShareNoteMap: vi.fn(() => ({})),
}))

import ShareCreateDialog from './ShareCreateDialog.vue'

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  missingWarn: false,
  fallbackWarn: false,
  messages: {
    en: {
      share: {
        createNew: 'New share', roleLabel: 'What can people do?',
        roleViewer: 'View only', roleContributor: 'View + add photos',
        roleViewerHint: 'h', roleContributorHint: 'h',
        name: 'Name', nameOptional: 'opt', noteLabel: 'Message', noteHint: 'h',
        create: 'Create link', creating: '…',
      },
    },
  },
})

const stubs = {
  Dialog: { props: ['visible'], template: '<div v-if="visible"><slot /></div>' },
  SelectButton: {
    props: ['options'],
    template: '<div class="select-button"><span v-for="o in options" :key="o.value" class="opt">{{ o.label }}</span></div>',
  },
  Button: { props: ['label'], template: '<button>{{ label }}</button>' },
  InputText: true,
  LocaleMarkdownEditor: true,
}

describe('ShareCreateDialog role switcher (cr-0116)', () => {
  it('offers view-only + view+add, never the uploader (that is a separate action)', () => {
    const w = mount(ShareCreateDialog, {
      props: { nodeId: 'n1', memoryKey: new Uint8Array([1, 2, 3]), memoryName: 'Rom' },
      global: { plugins: [i18n], stubs },
    })
    expect(w.text()).toContain('View only')
    expect(w.text()).toContain('View + add photos')
    expect(w.text()).not.toContain('Add photos only')
  })
})
