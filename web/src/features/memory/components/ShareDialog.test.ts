// @vitest-environment happy-dom
// uc.platform.capability-discovery sharing-first-consumer (web) — the Share dialog gates its role options on the
// sharing capabilities: with sharing.uploader-links absent from the manifest the Uploader drop-box option is
// hidden (the server also rejects createShare(UPLOADER) with 422). Component-level cover for the manual smoke.
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import { capabilityState } from '../../../shared/state/capabilities'
import { SHARING_PUBLIC_LINKS, SHARING_UPLOADER_LINKS } from '../../platform/capabilities'

// The dialog fetches its manage-list on mount; stub the share data layer so the component mounts network-free.
vi.mock('../share', () => ({
  createShare: vi.fn(),
  listSharesFor: vi.fn(() => Promise.resolve([])),
  revokeShare: vi.fn(),
}))

import ShareDialog from './ShareDialog.vue'

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  missingWarn: false,
  fallbackWarn: false,
  messages: {
    en: {
      share: {
        title: 'Share', intro: 'intro', roleLabel: 'What can people do?',
        roleViewer: 'View only', roleContributor: 'View + add photos', roleUploader: 'Add photos only',
        roleViewerHint: 'hint', roleContributorHint: 'hint', roleUploaderHint: 'hint',
        createLink: 'Create public link', existing: 'Active links', none: 'No public links yet.',
      },
    },
  },
})

// Follow the repo's dialog-test convention: stub the PrimeVue widgets (which otherwise need the PrimeVue service
// + teleport). The SelectButton stub renders each option's label so the gated set is assertable as text.
const stubs = {
  Dialog: { props: ['visible'], template: '<div v-if="visible"><slot /></div>' },
  SelectButton: {
    props: ['options', 'modelValue'],
    template: '<div class="select-button"><span v-for="o in options" :key="o.value" class="opt">{{ o.label }}</span></div>',
  },
  Button: { props: ['label'], template: '<button>{{ label }}</button>' },
}

const mountDialog = () =>
  mount(ShareDialog, {
    props: { nodeId: 'n1', memoryKey: new Uint8Array([1, 2, 3]), memoryName: 'Rom' },
    global: { plugins: [i18n], stubs },
  })

beforeEach(() => {
  capabilityState.ids = null
  capabilityState.major = null
  capabilityState.build = null
})

describe('ShareDialog role gate on sharing.uploader-links', () => {
  it('offers the Uploader option when the capability is active', () => {
    capabilityState.ids = [SHARING_PUBLIC_LINKS, SHARING_UPLOADER_LINKS]
    const w = mountDialog()
    expect(w.text()).toContain('View only')
    expect(w.text()).toContain('View + add photos')
    expect(w.text()).toContain('Add photos only')
  })

  it('hides the Uploader option when the capability is absent', () => {
    capabilityState.ids = [SHARING_PUBLIC_LINKS]
    const w = mountDialog()
    expect(w.text()).toContain('View only')
    expect(w.text()).toContain('View + add photos')
    expect(w.text()).not.toContain('Add photos only')
  })
})
