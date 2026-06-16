<script setup lang="ts">
// Live, operator-blind collaborative journal editing: a Yjs CRDT over the encrypted relay, bound to a
// CodeMirror editor with other members' live cursors (y-codemirror.next). The doc is reconstructed from the
// operator-blind store (sealed snapshot + sealed update log); local edits are broadcast live AND appended to
// the store (coalesced); on close we compact (fresh snapshot + Markdown reader projection). So non-overlapping
// sessions converge. Heavy deps (yjs/codemirror) load only when this component mounts.
import { onBeforeUnmount, onMounted, ref, shallowRef } from 'vue'
import * as Y from 'yjs'
import { EditorState } from '@codemirror/state'
import { EditorView, keymap, lineNumbers } from '@codemirror/view'
import { defaultKeymap, history, historyKeymap } from '@codemirror/commands'
import { markdown } from '@codemirror/lang-markdown'
import { yCollab } from 'y-codemirror.next'
import { session } from '../session'
import { t } from '../../i18n/i18n'
import { appendJournalUpdate, compactJournal, loadJournalDoc, PERSISTENCE_ORIGIN } from '../../features/memory/journalLive/journalPersistence'
import { EncryptedRelayProvider } from '../../features/memory/journalLive/encryptedProvider'
import type { ActiveSpace } from '../../features/home/home'

const props = defineProps<{ active: ActiveSpace; nodeId: string; initialMarkdown: string }>()
const emit = defineEmits<{ close: [markdown: string]; }>()

const editorEl = ref<HTMLElement | null>(null)
const connected = ref(false)
const peers = ref(0)
const doc = new Y.Doc()
const ytext = doc.getText('journal')
const provider = shallowRef<EncryptedRelayProvider | null>(null)
let view: EditorView | null = null
let maxSeq = 0            // highest store seq this client has applied/appended (the compaction watermark)
let pending: Uint8Array[] = []
let flushTimer: number | undefined
const scopeId = props.active.scope.scopeId

const COLORS = ['#5b4df5', '#0ea5e9', '#10b981', '#f59e0b', '#ef4444', '#ec4899', '#8b5cf6']
const color = COLORS[Math.floor(Math.random() * COLORS.length)]

function ctx() { return [session.apiBase, session.unlocked!, scopeId, props.nodeId, props.active.contentKey] as const }

function scheduleFlush() {
  window.clearTimeout(flushTimer)
  flushTimer = window.setTimeout(flush, 1200)
}
/** Append the coalesced local edits as one sealed update (idempotent on the CRDT, so re-queue on failure). */
async function flush() {
  if (!pending.length) return
  const merged = pending.length === 1 ? pending[0] : Y.mergeUpdates(pending)
  pending = []
  try {
    maxSeq = Math.max(maxSeq, await appendJournalUpdate(...ctx(), merged))
  } catch {
    pending.unshift(merged) // transient — the next edit/flush retries
  }
}

/** Persist only LOCAL edits: skip updates we applied from the store or that arrived over the relay. */
function onLocalUpdate(update: Uint8Array, origin: unknown) {
  if (origin === PERSISTENCE_ORIGIN || origin === provider.value) return
  pending.push(update)
  scheduleFlush()
}

onMounted(async () => {
  const wsBase = session.apiBase.replace(/^http/, 'ws').replace(/\/$/, '')
  const url = `${wsBase}/ws/journal/${scopeId}/${props.nodeId}?token=${encodeURIComponent(session.unlocked!.sessionToken)}`
  const p = new EncryptedRelayProvider(url, doc, props.active.contentKey)
  provider.value = p
  p.awareness.setLocalStateField('user', { name: session.unlocked!.user.displayName, color, colorLight: color + '33' })
  p.onStatus = (c) => { connected.value = c }
  p.awareness.on('change', () => { peers.value = Math.max(0, p.awareness.getStates().size - 1) })

  // Reconstruct from the operator-blind store, then bootstrap a legacy Markdown-only journal into the CRDT.
  try { maxSeq = await loadJournalDoc(...ctx(), doc) } catch { /* offline / first write — start empty */ }
  if (ytext.length === 0 && props.initialMarkdown.length > 0) doc.transact(() => ytext.insert(0, props.initialMarkdown))

  doc.on('update', onLocalUpdate)
  view = new EditorView({
    parent: editorEl.value!,
    state: EditorState.create({
      doc: ytext.toString(),
      extensions: [
        lineNumbers(),
        history(),
        keymap.of([...defaultKeymap, ...historyKeymap]),
        markdown(),
        EditorView.lineWrapping,
        yCollab(ytext, p.awareness),
      ],
    }),
  })
})

onBeforeUnmount(async () => {
  window.clearTimeout(flushTimer)
  doc.off('update', onLocalUpdate)
  await flush()
  // Compaction refreshes the snapshot + the Markdown reader projection and prunes the folded-in updates.
  try { await compactJournal(...ctx(), doc, ytext.toString(), maxSeq) } catch { /* best-effort; updates persist */ }
  view?.destroy()
  provider.value?.destroy()
  doc.destroy()
})

function close() {
  emit('close', ytext.toString())
}
</script>

<template>
  <div class="live-journal">
    <div class="live-journal-bar">
      <span class="live-dot" :class="{ on: connected }" />
      <span class="live-status">{{ connected ? t('journal.live') : t('journal.connecting') }}</span>
      <span v-if="peers > 0" class="live-peers">· {{ t('journal.peers', { n: peers }) }}</span>
      <span class="live-spacer" />
      <button class="btn btn-ghost" type="button" @click="close">{{ t('journal.done') }}</button>
    </div>
    <div ref="editorEl" class="live-journal-editor"></div>
  </div>
</template>
