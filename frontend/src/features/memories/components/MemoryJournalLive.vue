<script setup lang="ts">
// Live, operator-blind collaborative journal editing: a Yjs CRDT over the encrypted relay (every WS message
// AEAD-sealed under the content key), bound to a CodeMirror editor with other members' live cursors
// (y-codemirror.next). The doc is reconstructed from the operator-blind store (sealed snapshot + sealed update
// log); local edits broadcast live AND append to the store (coalesced); on close we compact (fresh snapshot +
// Markdown reader projection). Non-overlapping sessions converge. Heavy deps load only when this mounts.
import { onBeforeUnmount, onMounted, ref, shallowRef } from 'vue'
import * as Y from 'yjs'
import { EditorState } from '@codemirror/state'
import { EditorView, keymap, lineNumbers } from '@codemirror/view'
import { defaultKeymap, history, historyKeymap } from '@codemirror/commands'
import { markdown } from '@codemirror/lang-markdown'
import { yCollab } from 'y-codemirror.next'
import Button from 'primevue/button'
import { appendJournalUpdate, compactJournal, fetchJournalLiveTicket, loadJournalDoc, PERSISTENCE_ORIGIN } from '../../../features/memory/journalLive/journalPersistence'
import { EncryptedRelayProvider } from '../../../features/memory/journalLive/encryptedProvider'
import { journalEmbedEditing, journalEmbedMarkdown, refreshChips, replaceEmbed, type EmbedRange } from '../../../features/memory/journalLive/journalEmbedEditor'
import type { JournalEmbed, JournalEmbedKind } from '../../../features/memory/journal/journalEmbed'
import { getTracks } from '../../../features/memory/track/track'
import { listPlaylists, PROVIDER_LABEL } from '../../../features/memory/playlist/playlist'
import { listChildren, decodeNode } from '../../../features/memory/browse/browse'
import JournalEmbedPickerDialog from './JournalEmbedPickerDialog.vue'
import type { ActiveSpace } from '../../../features/home/home'
import { session } from '../../../app/session'
import { t } from '../../../i18n/i18n'

const props = defineProps<{ active: ActiveSpace; nodeId: string; initialMarkdown: string }>()
const emit = defineEmits<{ close: [markdown: string] }>()

const editorEl = ref<HTMLElement | null>(null)
const connected = ref(false)
const peers = ref(0)
const doc = new Y.Doc()
const ytext = doc.getText('journal')
const provider = shallowRef<EncryptedRelayProvider | null>(null)
let view: EditorView | null = null
let maxSeq = 0
let pending: Uint8Array[] = []
let flushTimer: number | undefined
const scopeId = props.active.scope.scopeId

const COLORS = ['#5b4df5', '#0ea5e9', '#10b981', '#f59e0b', '#ef4444', '#ec4899', '#8b5cf6']
const color = COLORS[Math.floor(Math.random() * COLORS.length)]

// --- Journal embeds: inline ::media/::track/::playlist/::chapter chips, inserted/edited via a picker ---
const EMBED_KINDS: JournalEmbedKind[] = ['media', 'track', 'playlist', 'chapter']
const EMBED_ICON: Record<JournalEmbedKind, string> = { media: 'pi pi-images', track: 'pi pi-map-marker', playlist: 'pi pi-volume-up', chapter: 'pi pi-bookmark' }
const picker = ref<{ kind: JournalEmbedKind; from: number; to: number; initial?: { ids: string[]; label?: string } } | null>(null)
const trackNames = ref<Record<string, string>>({})
const playlistNames = ref<Record<string, string>>({})
const chapterNames = ref<Record<string, string>>({})

function typeLabels(): Record<JournalEmbedKind, string> {
  return { media: t('journal.embedKind.media'), track: t('journal.embedKind.track'), playlist: t('journal.embedKind.playlist'), chapter: t('journal.embedKind.chapter') }
}

function chipText(embed: JournalEmbed): string {
  if (embed.label) return embed.label
  if (embed.kind === 'media') return `${embed.ids.length} · ${t('journal.embedKind.media')}`
  if (embed.kind === 'track') {
    const names = embed.ids.map((id) => trackNames.value[id]).filter(Boolean)
    return names.length ? names.join(', ') : t('journal.embedKind.track')
  }
  if (embed.kind === 'playlist') return playlistNames.value[embed.ids[0]] || t('journal.embedKind.playlist')
  return chapterNames.value[embed.ids[0]] || t('journal.embedKind.chapter')
}

function openInsert(kind: JournalEmbedKind, from: number, to: number) { picker.value = { kind, from, to } }
function openEdit(range: EmbedRange) { picker.value = { kind: range.kind, from: range.start, to: range.end, initial: { ids: range.ids, label: range.label } } }
function insertEmbed(kind: JournalEmbedKind) { const pos = view?.state.selection.main.head ?? 0; openInsert(kind, pos, pos) }

function onPickerSelect(res: { ids: string[]; label?: string }) {
  if (view && picker.value) replaceEmbed(view, picker.value.from, picker.value.to, journalEmbedMarkdown(picker.value.kind, res.ids, res.label))
  picker.value = null
}
function onPickerRemove() {
  if (view && picker.value) replaceEmbed(view, picker.value.from, picker.value.to, '')
  picker.value = null
}

/** Resolve track/playlist/chapter names so chips read well, then re-render chips. Best-effort. */
async function loadEmbedNames() {
  try {
    const [tracks, playlists, children] = await Promise.all([
      getTracks(session.apiBase, session.unlocked!, props.active, props.nodeId),
      listPlaylists(session.apiBase, session.unlocked!, props.active, props.nodeId),
      listChildren(session.apiBase, session.unlocked!, scopeId, props.nodeId),
    ])
    trackNames.value = Object.fromEntries(tracks.map((tr) => [tr.id, tr.name]))
    playlistNames.value = Object.fromEntries(playlists.map((p) => [p.id, p.title || PROVIDER_LABEL[p.provider]]))
    chapterNames.value = Object.fromEntries(children.filter((n) => n.type === 'CHAPTER').map((n) => [n.id, decodeNode(props.active.contentKey, n).name]))
    view?.dispatch({ effects: refreshChips.of() })
  } catch { /* names stay as fallbacks */ }
}

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

/** Persist only LOCAL edits: skip updates applied from the store or arrived over the relay. */
function onLocalUpdate(update: Uint8Array, origin: unknown) {
  if (origin === PERSISTENCE_ORIGIN || origin === provider.value) return
  pending.push(update)
  scheduleFlush()
}

onMounted(async () => {
  const wsBase = session.apiBase.replace(/^http/, 'ws').replace(/\/$/, '')
  // Authenticate the relay with a fresh short-lived single-use ticket on every (re)connect — never the
  // session token in the URL.
  const memberUrl = async () => {
    const ticket = await fetchJournalLiveTicket(session.apiBase, session.unlocked!)
    return `${wsBase}/ws/journal/${scopeId}/${props.nodeId}?ticket=${encodeURIComponent(ticket)}`
  }
  const p = new EncryptedRelayProvider(memberUrl, doc, props.active.contentKey)
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
        // before the default keymap so the chip-aware Backspace/Delete wins; renders embeds as atomic chips
        journalEmbedEditing({ chipText, typeLabels, onInsert: openInsert, onEdit: openEdit }),
        keymap.of([...defaultKeymap, ...historyKeymap]),
        markdown(),
        EditorView.lineWrapping,
        yCollab(ytext, p.awareness),
      ],
    }),
  })
  void loadEmbedNames()
})

onBeforeUnmount(() => {
  // Vue does not await async lifecycle hooks — tear down synchronously FIRST (editor + relay socket), then
  // persist detached on the still-alive doc (HTTP-only append/compact, no relay needed).
  window.clearTimeout(flushTimer)
  doc.off('update', onLocalUpdate)
  view?.destroy()
  provider.value?.destroy()
  void persistThenDestroy()
})

/** Best-effort: flush pending edits, reload the log so the snapshot is gap-free, compact, then free the doc. */
async function persistThenDestroy() {
  try {
    await flush()
    const coveredSeq = await loadJournalDoc(...ctx(), doc)
    await compactJournal(...ctx(), doc, ytext.toString(), coveredSeq)
  } catch {
    /* best-effort; the appended updates persist regardless */
  } finally {
    doc.destroy()
  }
}

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
      <span class="live-embed-group" :title="t('journal.insertEmbed')">
        <Button
          v-for="k in EMBED_KINDS"
          :key="k"
          type="button"
          size="small"
          severity="secondary"
          text
          :icon="EMBED_ICON[k]"
          :title="typeLabels()[k]"
          :aria-label="typeLabels()[k]"
          @click="insertEmbed(k)"
        />
      </span>
      <Button type="button" :label="t('journal.done')" icon="pi pi-check" size="small" @click="close" />
    </div>
    <div ref="editorEl" class="live-journal-editor"></div>

    <JournalEmbedPickerDialog
      v-if="picker"
      :kind="picker.kind"
      :active="active"
      :node-id="nodeId"
      :initial="picker.initial"
      @select="onPickerSelect"
      @remove="onPickerRemove"
      @cancel="picker = null"
    />
  </div>
</template>

<style scoped>
.live-journal { margin: 0.5rem 0 1rem; }
.live-journal-bar { display: flex; align-items: center; gap: 0.5rem; margin-bottom: 0.5rem; }
.live-dot { width: 0.6rem; height: 0.6rem; border-radius: 50%; background: var(--p-red-400, #f87171); }
.live-dot.on { background: var(--p-green-400, #34d399); }
.live-status { font-size: 0.9rem; opacity: 0.8; }
.live-peers { font-size: 0.9rem; opacity: 0.6; }
.live-spacer { flex: 1; }
.live-journal-editor { border: 1px solid var(--p-content-border-color, rgba(127,127,127,0.3)); border-radius: 8px; overflow: hidden; }
.live-journal-editor :deep(.cm-editor) { min-height: 16rem; max-height: 60vh; font-size: 0.95rem; }
.live-journal-editor :deep(.cm-scroller) { font-family: var(--font-mono, monospace); }
.live-journal-editor :deep(.cm-focused) { outline: none; }
.live-embed-group { display: inline-flex; gap: 0.1rem; }
.live-journal-editor :deep(.journal-chip) { display: inline-flex; align-items: center; gap: 0.3rem; margin: 0 1px; padding: 0.05rem 0.45rem; border-radius: 999px; border: 1px solid rgba(99,102,241,0.9); background: rgba(99,102,241,0.16); color: inherit; font: inherit; font-size: 0.85em; cursor: pointer; vertical-align: baseline; }
.live-journal-editor :deep(.journal-chip:hover) { background: rgba(99,102,241,0.28); }
.live-journal-editor :deep(.journal-chip-pending) { border-color: var(--p-red-400, #f87171); background: rgba(248,113,113,0.2); }
.live-journal-editor :deep(.journal-chip-edit) { opacity: 0.6; font-size: 0.85em; }
</style>
