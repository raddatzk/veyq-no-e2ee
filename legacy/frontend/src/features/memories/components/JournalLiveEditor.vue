<script setup lang="ts">
// Operator-blind collaborative journal editor. A Yjs document is bound to CodeMirror (y-codemirror) and
// synced two ways: live peers via the EncryptedRelayProvider (every relay frame AEAD-sealed under the scope
// content key), and durably via the REST update log (appendJournalUpdate per local edit, compactJournal on
// close → fresh snapshot + the Markdown reader projection the plain renderer reads). The server never sees
// plaintext on either path.
import { onBeforeUnmount, onMounted, ref, shallowRef } from 'vue'
import { useI18n } from 'vue-i18n'
import { EditorState } from '@codemirror/state'
import { EditorView, keymap, lineNumbers, placeholder as cmPlaceholder } from '@codemirror/view'
import { defaultKeymap, history, historyKeymap } from '@codemirror/commands'
import { markdown } from '@codemirror/lang-markdown'
import { yCollab } from 'y-codemirror.next'
import * as Y from 'yjs'
import { EncryptedRelayProvider } from '../../memory/journalLive/encryptedProvider'
import {
  appendJournalUpdate,
  compactJournal,
  fetchJournalLiveTicket,
  loadJournalDoc,
  PERSISTENCE_ORIGIN,
} from '../../memory/journalLive/journalPersistence'
import type { UnlockedSession } from '../../account/login/login'

const props = defineProps<{
  apiBase: string
  session: UnlockedSession
  scopeId: string
  nodeId: string
  contentKey: Uint8Array
}>()
const emit = defineEmits<{ saved: [markdown: string] }>()

const { t } = useI18n()
const host = ref<HTMLElement | null>(null)
const status = ref<'connecting' | 'connected' | 'offline'>('connecting')
const saving = ref(false)

const doc = new Y.Doc()
const ytext = doc.getText('journal')
let view: EditorView | null = null
let provider: EncryptedRelayProvider | null = null
let coveredSeq = 0
let saveTimer: ReturnType<typeof setTimeout> | null = null
let destroyed = false

function wsBase(): string {
  if (props.apiBase) return props.apiBase.replace(/^http/, 'ws').replace(/\/$/, '')
  return `${location.protocol === 'https:' ? 'wss:' : 'ws:'}//${location.host}`
}

async function persistLocal(update: Uint8Array) {
  try {
    coveredSeq = await appendJournalUpdate(props.apiBase, props.session, props.scopeId, props.nodeId, props.contentKey, update)
    scheduleCompaction()
  } catch {
    /* offline / transient — the relay still carries the edit to live peers; compaction retries later */
  }
}

function scheduleCompaction() {
  if (saveTimer) clearTimeout(saveTimer)
  saveTimer = setTimeout(() => void compact(), 1500)
}

async function compact() {
  if (destroyed) return
  saving.value = true
  try {
    const md = ytext.toString()
    await compactJournal(props.apiBase, props.session, props.scopeId, props.nodeId, props.contentKey, doc, md, coveredSeq)
    emit('saved', md)
  } catch {
    /* leave the update log in place; a later compaction (or unmount) will fold it in */
  } finally {
    saving.value = false
  }
}

onMounted(async () => {
  // 1) reconstruct the document from the durable store
  coveredSeq = await loadJournalDoc(props.apiBase, props.session, props.scopeId, props.nodeId, props.contentKey, doc)

  // 2) live peer sync over the sealed relay (fresh single-use ticket per connect)
  provider = new EncryptedRelayProvider(
    async () => `${wsBase()}/ws/journal/${props.scopeId}/${props.nodeId}?ticket=${encodeURIComponent(await fetchJournalLiveTicket(props.apiBase, props.session))}`,
    doc,
    props.contentKey,
  )
  provider.onStatus = (connected) => { status.value = connected ? 'connected' : 'offline' }

  // 3) durably persist every LOCAL edit (relay holds no state); skip store-loaded + remote-peer updates
  doc.on('update', (update, origin) => {
    if (origin === PERSISTENCE_ORIGIN || origin === provider) return
    void persistLocal(update)
  })

  // 4) bind CodeMirror to the shared text
  view = new EditorView({
    parent: host.value!,
    state: EditorState.create({
      doc: ytext.toString(),
      extensions: [
        lineNumbers(),
        history(),
        markdown(),
        keymap.of([...defaultKeymap, ...historyKeymap]),
        cmPlaceholder(t('journalView.placeholder')),
        yCollab(ytext, provider.awareness),
        EditorView.lineWrapping,
      ],
    }),
  })
})

onBeforeUnmount(async () => {
  destroyed = true
  if (saveTimer) clearTimeout(saveTimer)
  await compact() // final fold-in so the read projection is current
  view?.destroy()
  provider?.destroy()
  doc.destroy()
})
</script>

<template>
  <div class="journal-editor">
    <div class="journal-editor-status">
      <span class="status-dot" :class="status" />
      <span>{{ saving ? t('journalView.saving') : t(`journalView.status.${status}`) }}</span>
    </div>
    <div ref="host" class="journal-editor-host" />
  </div>
</template>

<style scoped>
.journal-editor-status {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  font-size: 0.85rem;
  opacity: 0.75;
  margin-bottom: 0.5rem;
}
.status-dot {
  width: 0.6rem;
  height: 0.6rem;
  border-radius: 50%;
  background: var(--p-yellow-400, #facc15);
}
.status-dot.connected {
  background: var(--p-green-400, #4ade80);
}
.status-dot.offline {
  background: var(--p-red-400, #f87171);
}
.journal-editor-host {
  border: 1px solid var(--p-content-border-color, rgba(127, 127, 127, 0.25));
  border-radius: 10px;
  overflow: hidden;
}
.journal-editor-host :deep(.cm-editor) {
  min-height: 50vh;
  font-size: 0.95rem;
}
.journal-editor-host :deep(.cm-focused) {
  outline: none;
}
</style>
