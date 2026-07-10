// @vitest-environment happy-dom
// The share CREATE dialog is shared by both flows via the `uploader` flag. The NORMAL flow shows a VIEWER/CONTRIBUTOR
// role switcher (never the uploader — that is its own action/dialog); the UPLOADER flow hides the switcher entirely
// (role fixed to UPLOADER). That absence of the switcher is the ONLY difference between the two dialogs. The uploader
// action itself is capability-gated in MemoryDetail, and the server 422-rejects createShare(UPLOADER) when off.
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
        createNew: 'New share', uploaderCreate: 'New upload link', roleLabel: 'What can people do?',
        roleViewer: 'View only', roleContributor: 'View + add photos', roleUploader: 'Add photos only',
        roleViewerHint: 'h', roleContributorHint: 'h', roleUploaderHint: 'A drop-box.',
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

function mountDialog(props: Record<string, unknown>) {
  return mount(ShareCreateDialog, {
    props: { nodeId: 'n1', memoryKey: new Uint8Array([1, 2, 3]), memoryName: 'Rom', ...props },
    global: { plugins: [i18n], stubs },
  })
}

describe('ShareCreateDialog (cr-0116)', () => {
  it('normal flow: the switcher offers view-only + view+add, never the uploader', () => {
    const w = mountDialog({})
    expect(w.find('.select-button').exists()).toBe(true)
    expect(w.text()).toContain('View only')
    expect(w.text()).toContain('View + add photos')
    expect(w.text()).not.toContain('Add photos only')
  })

  it('uploader flow: no role switcher at all (role fixed to UPLOADER)', () => {
    const w = mountDialog({ uploader: true })
    expect(w.find('.select-button').exists()).toBe(false)
  })
})
