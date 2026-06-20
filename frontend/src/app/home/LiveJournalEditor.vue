<script setup lang="ts">
// Live, operator-blind collaborative journal editing: a Yjs CRDT over the encrypted relay, bound to a
// CodeMirror editor with other members' live cursors (y-codemirror.next). The doc is reconstructed from the
// operator-blind store (sealed snapshot + sealed update log); local edits are broadcast live AND appended to
// the store (coalesced); on close we compact (fresh snapshot + Markdown reader projection). So non-overlapping
// sessions converge. Heavy deps (yjs/codemirror) load only when this component mounts.
import { computed, onBeforeUnmount, onMounted, ref, shallowRef } from 'vue'
import * as Y from 'yjs'
import { EditorState } from '@codemirror/state'
import { EditorView, keymap, lineNumbers } from '@codemirror/view'
import { defaultKeymap, history, historyKeymap } from '@codemirror/commands'
import { markdown } from '@codemirror/lang-markdown'
import { yCollab } from 'y-codemirror.next'
import { session } from '../session'
import { t } from '../../i18n/i18n'
import { appendJournalUpdate, compactJournal, fetchJournalLiveTicket, loadJournalDoc, PERSISTENCE_ORIGIN } from '../../features/memory/journalLive/journalPersistence'
import { EncryptedRelayProvider } from '../../features/memory/journalLive/encryptedProvider'
import { journalEmbedEditing, refreshChips, replaceEmbed, type EmbedRange } from '../../features/memory/journalLive/journalEmbedEditor'
import { journalEmbedMarkdown, type JournalEmbed, type JournalEmbedKind } from '../../features/memory/journal/journalEmbed'
import { loadGalleryTiles, type GalleryTile } from '../../features/gallery/gallery'
import { getTracks, type Track } from '../../features/memory/track/track'
import { listPlaylists, PROVIDER_LABEL, type Playlist } from '../../features/memory/playlist/playlist'
import { loadLevel, type ActiveSpace } from '../../features/home/home'
import JournalMediaPicker from './JournalMediaPicker.vue'
import JournalEmbedPicker from './JournalEmbedPicker.vue'

const props = defineProps<{ active: ActiveSpace; nodeId: string; initialMarkdown: string }>()
const emit = defineEmits<{ close: [markdown: string]; }>()

// ---- embed resolution data (for chip labels + pickers), loaded once after mount -------------------------
const tiles = ref<GalleryTile[]>([])
const tracks = ref<Track[]>([])
const chapters = ref<{ id: string; name: string }[]>([])
const playlists = ref<Playlist[]>([])
const trackName = (id: string) => tracks.value.find((tr) => tr.id === id)?.name
const chapterName = (id: string) => chapters.value.find((c) => c.id === id)?.name
const playlistName = (id: string) => { const p = playlists.value.find((x) => x.id === id); return p ? (p.title || p.url) : undefined }

// ---- picker state ---------------------------------------------------------------------------------------
const picker = ref<{ kind: JournalEmbedKind; range: { from: number; to: number }; editing: boolean; ids: string[]; label: string } | null>(null)
const embedMenuOpen = ref(false)

function chipText(e: JournalEmbed): string {
  if (e.kind === 'media') return `${t('journal.embedKind.media')} · ${e.label || t('journal.chipMediaCount', { n: e.ids.length })}`
  if (e.kind === 'track') {
    const fallback = e.ids.length > 1 ? t('journal.chipTrackCount', { n: e.ids.length }) : (trackName(e.ids[0]) ?? e.ids[0])
    return `${t('journal.embedKind.track')} · ${e.label || fallback}`
  }
  if (e.kind === 'playlist') return `${t('journal.embedKind.playlist')} · ${e.label || playlistName(e.ids[0]) || e.ids[0]}`
  return `${t('journal.embedKind.chapter')} · ${e.label || chapterName(e.ids[0]) || e.ids[0]}`
}
function typeLabels(): Record<JournalEmbedKind, string> {
  return { media: t('journal.embedKind.media'), track: t('journal.embedKind.track'), chapter: t('journal.embedKind.chapter'), playlist: t('journal.embedKind.playlist') }
}
const embedOptions = computed<{ kind: JournalEmbedKind; iconClass: string; label: string }[]>(() => [
  { kind: 'media', iconClass: 'veyq-glyph-memory', label: t('journal.embedKind.media') },
  { kind: 'track', iconClass: 'veyq-glyph-track', label: t('journal.embedKind.track') },
  { kind: 'chapter', iconClass: 'veyq-glyph-folder', label: t('journal.embedKind.chapter') },
  { kind: 'playlist', iconClass: 'veyq-glyph-playlist', label: t('journal.embedKind.playlist') },
])
function openInsert(kind: JournalEmbedKind, from: number, to: number) {
  embedMenuOpen.value = false
  picker.value = { kind, range: { from, to }, editing: false, ids: [], label: '' }
}
function openEdit(range: EmbedRange) {
  picker.value = { kind: range.kind, range: { from: range.start, to: range.end }, editing: true, ids: range.ids, label: range.label ?? '' }
}
/** Toolbar menu: insert an embed at the current cursor (no `::` typed). */
function insertAtCursor(kind: JournalEmbedKind) {
  const at = view?.state.selection.main.head ?? 0
  openInsert(kind, at, at)
}
function applyPicker(ids: string[], label: string) {
  if (!picker.value || !view) return
  const { kind, range } = picker.value
  replaceEmbed(view, range.from, range.to, journalEmbedMarkdown(kind, ids, label))
  picker.value = null
}
function removePicker() {
  if (!picker.value || !view) return
  const { range } = picker.value
  replaceEmbed(view, range.from, range.to, '')
  picker.value = null
}

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
        // Before the default keymap so its atomic-delete Backspace/Delete handlers win at a chip boundary.
        journalEmbedEditing({ chipText, typeLabels, onInsert: openInsert, onEdit: openEdit }),
        keymap.of([...defaultKeymap, ...historyKeymap]),
        markdown(),
        EditorView.lineWrapping,
        yCollab(ytext, p.awareness),
      ],
    }),
  })
  void loadEmbedData()
})

/** Load this memory's media/tracks/chapters for chip labels + pickers, then refresh the rendered chips. */
async function loadEmbedData() {
  try {
    const [t1, t2, lvl, pls] = await Promise.all([
      loadGalleryTiles(session.apiBase, session.unlocked!, props.active, props.nodeId),
      getTracks(session.apiBase, session.unlocked!, props.active, props.nodeId),
      loadLevel(session.apiBase, session.unlocked!, props.active, props.nodeId),
      listPlaylists(session.apiBase, session.unlocked!, props.active, props.nodeId).catch(() => []),
    ])
    tiles.value = t1
    tracks.value = t2
    chapters.value = lvl.filter((e) => e.node.type === 'CHAPTER').map((e) => ({ id: e.node.id, name: e.name }))
    playlists.value = pls
    view?.dispatch({ effects: refreshChips.of() })
  } catch { /* labels fall back to ids; pickers show empty states */ }
}

onBeforeUnmount(() => {
  // Vue does not await async lifecycle hooks, so tear down synchronously FIRST: stop the editor + the relay
  // socket immediately rather than holding them alive across the persist round-trips below. The persist runs
  // detached on the (still-alive) doc — HTTP-only (append/compact), so it doesn't need the relay.
  window.clearTimeout(flushTimer)
  doc.off('update', onLocalUpdate)
  view?.destroy()
  provider.value?.destroy()
  tiles.value.forEach((t) => URL.revokeObjectURL(t.url)) // picker/chip thumbnails are object URLs
  void persistThenDestroy()
})

/** Best-effort: flush pending edits, reload the log so the snapshot is gap-free, compact, then free the doc. */
async function persistThenDestroy() {
  try {
    await flush()
    // Reload the full log into the doc FIRST so the snapshot covers every update <= coveredSeq — otherwise a
    // seq another editor appended that this doc never folded in (e.g. an HTTP append while our relay lagged)
    // would be pruned but missing from the snapshot, losing that content.
    const coveredSeq = await loadJournalDoc(...ctx(), doc)
    await compactJournal(...ctx(), doc, ytext.toString(), coveredSeq)
  } catch { /* best-effort; updates persist */ } finally {
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
      <div class="journal-embed-menu">
        <button class="btn btn-ghost journal-embed-trigger" type="button" aria-haspopup="menu" :aria-expanded="embedMenuOpen" @click="embedMenuOpen = !embedMenuOpen">
          <span class="journal-embed-trigger-icon" aria-hidden="true">＋</span>
          <span>{{ t('journal.embed') }}</span>
        </button>
        <div v-if="embedMenuOpen" class="journal-embed-menu-pop" role="menu" @click.self="embedMenuOpen = false">
          <button v-for="option in embedOptions" :key="option.kind" class="btn btn-ghost journal-embed-option" type="button" role="menuitem" @click="insertAtCursor(option.kind)">
            <span class="journal-embed-option-icon veyq-glyph" :class="option.iconClass" aria-hidden="true"></span>
            <span class="journal-embed-option-label">{{ option.label }}</span>
          </button>
        </div>
      </div>
      <button class="btn btn-ghost" type="button" @click="close">{{ t('journal.done') }}</button>
    </div>
    <div ref="editorEl" class="live-journal-editor"></div>

    <JournalMediaPicker
      v-if="picker && picker.kind === 'media'"
      :tiles="tiles"
      :selected-ids="picker.ids"
      :caption="picker.label"
      :editing="picker.editing"
      @confirm="applyPicker"
      @remove="removePicker"
      @cancel="picker = null"
    />
    <JournalEmbedPicker
      v-else-if="picker && picker.kind === 'track'"
      kind="track"
      :items="tracks.map((tr) => ({ id: tr.id, name: tr.name }))"
      :selected-ids="picker.ids"
      :label="picker.label"
      :editing="picker.editing"
      @confirm="applyPicker"
      @remove="removePicker"
      @cancel="picker = null"
    />
    <JournalEmbedPicker
      v-else-if="picker && picker.kind === 'chapter'"
      kind="chapter"
      :items="chapters"
      :selected-ids="picker.ids"
      :label="picker.label"
      :editing="picker.editing"
      @confirm="applyPicker"
      @remove="removePicker"
      @cancel="picker = null"
    />
    <JournalEmbedPicker
      v-else-if="picker && picker.kind === 'playlist'"
      kind="playlist"
      :items="playlists.map((p) => ({ id: p.id, name: (p.title || p.url) + ' · ' + PROVIDER_LABEL[p.provider] }))"
      :selected-ids="picker.ids"
      :label="picker.label"
      :editing="picker.editing"
      @confirm="applyPicker"
      @remove="removePicker"
      @cancel="picker = null"
    />
  </div>
</template>
