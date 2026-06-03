<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { autocompletion } from '@codemirror/autocomplete'
import { defaultKeymap, history, historyKeymap } from '@codemirror/commands'
import { markdown } from '@codemirror/lang-markdown'
import { EditorState, RangeSetBuilder, Transaction } from '@codemirror/state'
import {
  Decoration,
  EditorView,
  keymap,
  placeholder as editorPlaceholder,
  ViewPlugin,
  WidgetType,
  type DecorationSet,
  type ViewUpdate
} from '@codemirror/view'
import { HocuspocusProvider } from '@hocuspocus/provider'
import { yCollab } from 'y-codemirror.next'
import * as Y from 'yjs'
import { useI18n } from 'vue-i18n'
import type { MemoryJournalLiveSession } from '../api'
import JournalRenderer from './JournalRenderer.vue'
import type { Memory, MemoryMedia, MemoryPlaylist, MemoryTrack } from '../api'
import {
  journalEmbedRangeAtPosition,
  journalEmbedMarkdown,
  journalEmbedRanges,
  journalEmbedTriggerRange,
  mediaTagRangeAtPosition,
  mediaTriggerRange,
  type JournalEmbedKind,
  type JournalEmbedRange,
  type MediaInsertRange
} from '../utils/journalMediaMarkdown'
import { createJournalEmbedTypeCompletionSource } from '../utils/journalEmbedCompletion'

type JournalLiveStatus = 'connecting' | 'connected' | 'saving' | 'saved' | 'offline' | 'error'

const SAVE_STATUS_DELAY_MS = 1500

const props = defineProps<{
  modelValue: string
  session: MemoryJournalLiveSession | null
  media: MemoryMedia[]
  childMemories: Memory[]
  playlists?: MemoryPlaylist[]
  tracks?: MemoryTrack[]
  placeholder?: string
}>()

const emit = defineEmits<{
  'update:modelValue': [value: string]
  status: [value: JournalLiveStatus]
  error: [message: string]
  fallback: []
  'media-trigger': [range: MediaInsertRange]
  'media-tag-edit': [range: MediaInsertRange]
  'embed-trigger': [range: JournalEmbedRange]
  'embed-tag-edit': [range: JournalEmbedRange]
  openMemory: [memory: Memory]
}>()

const { t, locale } = useI18n()
const editorHost = ref<HTMLElement | null>(null)
const status = ref<JournalLiveStatus>('connecting')
const collaborators = ref<Array<{ clientId: number; name: string; color: string }>>([])
let view: EditorView | null = null
let provider: HocuspocusProvider | null = null
let yDocument: Y.Doc | null = null
let yText: Y.Text | null = null
let localUpdate = false
let hasConnected = false
let fallbackTimer: number | null = null
let savedStatusTimer: number | null = null
let lastMediaTriggerPosition: number | null = null
let lastJournalEmbedTriggerPosition: number | null = null
let pendingJournalEmbedDeleteRange: { start: number; end: number } | null = null

const providerUrl = computed(() => {
  if (!props.session?.websocketUrl) return ''
  if (props.session.websocketUrl.startsWith('ws')) return props.session.websocketUrl
  const base = `${window.location.protocol === 'https:' ? 'wss:' : 'ws:'}//${window.location.host}`
  return `${base}${props.session.websocketUrl.startsWith('/') ? '' : '/'}${props.session.websocketUrl}`
})
const statusLabel = computed(() => t(`memory.journalLiveStatus.${status.value}`))

onMounted(() => {
  startEditor()
})

onBeforeUnmount(() => {
  stopEditor()
})

watch(
  () => props.session?.documentId,
  () => {
    stopEditor()
    void nextTick(startEditor)
  }
)

function startEditor() {
  if (!editorHost.value || !props.session) return
  setStatus('connecting')

  yDocument = new Y.Doc()
  yText = yDocument.getText('markdown')

  provider = new HocuspocusProvider({
    url: providerUrl.value,
    name: props.session.documentId,
    token: props.session.token,
    document: yDocument
  })

  const awareness = provider.awareness
  if (!awareness) {
    setStatus('error')
    emit('error', t('memory.journalLiveInitFailed'))
    emit('fallback')
    return
  }
  fallbackTimer = window.setTimeout(() => {
    if (hasConnected) return
    setStatus('offline')
    emit('fallback')
  }, 6000)
  awareness.setLocalStateField('user', {
    name: props.session.displayName,
    color: props.session.color
  })
  awareness.on('change', updateCollaborators)
  provider.on('status', ({ status: nextStatus }: { status: string }) => {
    if (nextStatus === 'connected') {
      hasConnected = true
      clearFallbackTimer()
      setStatus('connected')
    } else {
      setStatus('offline')
    }
  })
  provider.on('synced', () => {
    hasConnected = true
    clearFallbackTimer()
    setStatus('saved')
  })
  provider.on('authenticationFailed', () => {
    setStatus('error')
    emit('error', t('memory.journalLiveAuthFailed'))
    emit('fallback')
  })
  provider.on('error', () => {
    if (hasConnected) return
    setStatus('error')
    emit('fallback')
  })

  const state = EditorState.create({
    extensions: [
      history(),
      markdown(),
      editorPlaceholder(props.placeholder || ''),
      keymap.of([
        { key: 'Backspace', run: handleJournalEmbedBackspace },
        { key: 'Delete', run: handleJournalEmbedDelete },
        ...defaultKeymap,
        ...historyKeymap
      ]),
      EditorView.lineWrapping,
      yCollab(yText, awareness),
      journalEmbedAutocompleteExtension(),
      mediaTagEditExtension(
        (range) => emit('media-tag-edit', range),
        (range) => emit('embed-tag-edit', range),
        t('common.edit'),
        (count) => t(count === 1 ? 'memory.journalMediaElementSingular' : 'memory.journalMediaElementPlural', { count }),
        embedChipLabel
      ),
      EditorView.updateListener.of((update) => {
        if (update.selectionSet && pendingJournalEmbedDeleteRange) {
          const position = update.state.selection.main.head
          if (
            position !== pendingJournalEmbedDeleteRange.start &&
            position !== pendingJournalEmbedDeleteRange.end
          ) {
            resetPendingJournalEmbedDelete()
          }
        }
        if (!update.docChanged) return
        resetPendingJournalEmbedDelete()
        localUpdate = true
        const value = update.state.doc.toString()
        emit('update:modelValue', value)
        markSaving()
        queueMicrotask(() => {
          localUpdate = false
        })
        const position = update.state.selection.main.head
        const range = mediaTriggerRange(value, position)
        if (range) {
          if (lastMediaTriggerPosition === position) return
          lastMediaTriggerPosition = position
          emit('media-trigger', range)
          return
        }
        const embedRange = journalEmbedTriggerRange(value, position)
        if (!embedRange) return
        if (lastJournalEmbedTriggerPosition === position) return
        lastJournalEmbedTriggerPosition = position
        emit('embed-trigger', embedRange)
      })
    ]
  })
  view = new EditorView({ state, parent: editorHost.value })
}

function stopEditor() {
  clearFallbackTimer()
  clearSavedStatusTimer()
  hasConnected = false
  lastMediaTriggerPosition = null
  lastJournalEmbedTriggerPosition = null
  resetPendingJournalEmbedDelete()
  provider?.awareness?.off('change', updateCollaborators)
  provider?.destroy()
  provider = null
  view?.destroy()
  view = null
  yDocument?.destroy()
  yDocument = null
  yText = null
  collaborators.value = []
}

function journalEmbedAutocompleteExtension() {
  return autocompletion({
    activateOnTyping: true,
    interactionDelay: 0,
    override: [createJournalEmbedTypeCompletionSource({
      media: () => props.media.length > 0,
      chapter: () => props.childMemories.length > 0,
      playlist: () => Boolean(props.playlists?.length),
      track: () => Boolean(props.tracks?.length),
      labels: () => ({
        media: t('memory.journalHelpMedia'),
        chapter: t('memory.journalHelpMemory'),
        playlist: t('memory.journalHelpPlaylist'),
        track: t('memory.journalHelpTrack')
      }),
      onMedia: (range) => emit('media-trigger', range),
      onEmbed: (range) => emit('embed-trigger', range)
    })]
  })
}

function setStatus(nextStatus: JournalLiveStatus) {
  if (nextStatus !== 'saving') clearSavedStatusTimer()
  status.value = nextStatus
  emit('status', nextStatus)
}

function markSaving() {
  clearSavedStatusTimer()
  setStatus('saving')
  savedStatusTimer = window.setTimeout(() => {
    savedStatusTimer = null
    if (!hasConnected || status.value !== 'saving') return
    setStatus('saved')
  }, SAVE_STATUS_DELAY_MS)
}

function updateCollaborators() {
  if (!provider) return
  if (!provider.awareness) return
  collaborators.value = Array.from(provider.awareness.getStates().entries())
    .filter(([clientId]) => clientId !== provider?.awareness?.clientID)
    .map(([clientId, state]) => ({
      clientId,
      name: state.user?.name || 'Editor',
      color: state.user?.color || '#5b6ee1'
    }))
}

function clearFallbackTimer() {
  if (fallbackTimer === null) return
  window.clearTimeout(fallbackTimer)
  fallbackTimer = null
}

function clearSavedStatusTimer() {
  if (savedStatusTimer === null) return
  window.clearTimeout(savedStatusTimer)
  savedStatusTimer = null
}

function insertMarkdown(markdownText: string, range?: MediaInsertRange | JournalEmbedRange) {
  if (!view) return
  resetPendingJournalEmbedDelete()
  const selection = view.state.selection.main
  const from = range?.start ?? selection.from
  const to = range?.end ?? selection.to
  view.dispatch({
    changes: { from, to, insert: markdownText },
    selection: { anchor: from + markdownText.length },
    annotations: Transaction.userEvent.of('input.type')
  })
  view.focus()
}

function handleJournalEmbedBackspace(editorView: EditorView): boolean {
  return handleJournalEmbedDeleteKey(editorView, 'backward')
}

function handleJournalEmbedDelete(editorView: EditorView): boolean {
  return handleJournalEmbedDeleteKey(editorView, 'forward')
}

function handleJournalEmbedDeleteKey(editorView: EditorView, direction: 'backward' | 'forward'): boolean {
  const selection = editorView.state.selection.main
  if (!selection.empty) {
    resetPendingJournalEmbedDelete()
    return false
  }
  const position = selection.head
  const range = journalEmbedRanges(editorView.state.doc.toString()).find((embed) =>
    direction === 'backward' ? embed.end === position : embed.start === position
  )
  if (!range) {
    resetPendingJournalEmbedDelete()
    return false
  }
  if (
    pendingJournalEmbedDeleteRange &&
    pendingJournalEmbedDeleteRange.start === range.start &&
    pendingJournalEmbedDeleteRange.end === range.end
  ) {
    resetPendingJournalEmbedDelete()
    editorView.dispatch({
      changes: { from: range.start, to: range.end, insert: '' },
      selection: { anchor: range.start },
      annotations: Transaction.userEvent.of(direction === 'backward' ? 'delete.backward' : 'delete.forward')
    })
    return true
  }
  highlightPendingJournalEmbedDelete(range)
  return true
}

function highlightPendingJournalEmbedDelete(range: { start: number; end: number }) {
  resetPendingJournalEmbedDelete()
  pendingJournalEmbedDeleteRange = { start: range.start, end: range.end }
  editorHost.value
    ?.querySelector(`[data-journal-embed-start="${range.start}"][data-journal-embed-end="${range.end}"]`)
    ?.classList.add('memory-journal-embed-delete-pending')
}

function resetPendingJournalEmbedDelete() {
  if (!pendingJournalEmbedDeleteRange) return
  editorHost.value
    ?.querySelectorAll('.memory-journal-embed-delete-pending')
    .forEach((element) => element.classList.remove('memory-journal-embed-delete-pending'))
  pendingJournalEmbedDeleteRange = null
}

function currentMediaTagRange(): MediaInsertRange | null {
  if (!view) return null
  return mediaTagRangeAtPosition(view.state.doc.toString(), view.state.selection.main.head)
}

function currentJournalEmbedRange(): JournalEmbedRange | null {
  if (!view) return null
  return journalEmbedRangeAtPosition(view.state.doc.toString(), view.state.selection.main.head)
}

class MediaTagEditWidget extends WidgetType {
  constructor(
    private readonly range: MediaInsertRange,
    private readonly text: string,
    private readonly label: string,
    private readonly onEdit: (range: MediaInsertRange) => void
  ) {
    super()
  }

  eq(other: MediaTagEditWidget) {
    return this.range.start === other.range.start &&
      this.range.end === other.range.end &&
      this.text === other.text &&
      this.label === other.label
  }

  toDOM() {
    const button = globalThis.document.createElement('button')
    button.type = 'button'
    button.className = 'memory-journal-media-edit-button'
    button.dataset.journalEmbedStart = String(this.range.start)
    button.dataset.journalEmbedEnd = String(this.range.end)
    button.title = this.label
    button.setAttribute('aria-label', this.label)
    button.addEventListener('mousedown', (event) => {
      event.preventDefault()
    })
    button.addEventListener('click', (event) => {
      event.preventDefault()
      event.stopPropagation()
      this.onEdit(this.range)
    })

    const text = globalThis.document.createElement('span')
    text.className = 'memory-journal-media-edit-label'
    text.textContent = this.text
    button.append(text)

    const icon = globalThis.document.createElement('span')
    icon.className = 'pi pi-pencil'
    icon.setAttribute('aria-hidden', 'true')
    button.append(icon)
    return button
  }
}

class EmbedChipWidget extends WidgetType {
  constructor(
    private readonly range: JournalEmbedRange,
    private readonly text: string,
    private readonly label: string,
    private readonly onEdit: (range: JournalEmbedRange) => void
  ) {
    super()
  }

  eq(other: EmbedChipWidget) {
    return this.range.start === other.range.start &&
      this.range.end === other.range.end &&
      this.text === other.text &&
      this.label === other.label
  }

  toDOM() {
    const button = globalThis.document.createElement('button')
    button.type = 'button'
    button.className = 'memory-journal-embed-edit-button memory-journal-embed-chip'
    button.dataset.journalEmbedStart = String(this.range.start)
    button.dataset.journalEmbedEnd = String(this.range.end)
    button.title = this.label
    button.setAttribute('aria-label', this.label)
    button.addEventListener('mousedown', (event) => {
      event.preventDefault()
    })
    button.addEventListener('click', (event) => {
      event.preventDefault()
      event.stopPropagation()
      this.onEdit(this.range)
    })

    const text = globalThis.document.createElement('span')
    text.className = 'memory-journal-embed-edit-label'
    text.textContent = this.text
    button.append(text)

    const icon = globalThis.document.createElement('span')
    icon.className = 'pi pi-pencil'
    icon.setAttribute('aria-hidden', 'true')
    button.append(icon)
    return button
  }
}

function mediaTagEditExtension(
  onEdit: (range: MediaInsertRange) => void,
  onEmbedEdit: (range: JournalEmbedRange) => void,
  label: string,
  countLabel: (count: number) => string,
  embedLabel: (kind: JournalEmbedKind, value: string, label: string) => string
) {
  return ViewPlugin.fromClass(
    class {
      decorations: DecorationSet

      constructor(editorView: EditorView) {
        this.decorations = journalEmbedDecorations(editorView, onEdit, onEmbedEdit, label, countLabel, embedLabel)
      }

      update(update: ViewUpdate) {
        if (!update.docChanged && !update.viewportChanged) return
        this.decorations = journalEmbedDecorations(update.view, onEdit, onEmbedEdit, label, countLabel, embedLabel)
      }
    },
    {
      decorations: (plugin) => plugin.decorations
    }
  )
}

function journalEmbedDecorations(
  editorView: EditorView,
  onEdit: (range: MediaInsertRange) => void,
  onEmbedEdit: (range: JournalEmbedRange) => void,
  label: string,
  countLabel: (count: number) => string,
  embedLabel: (kind: JournalEmbedKind, value: string, label: string) => string
): DecorationSet {
  const builder = new RangeSetBuilder<Decoration>()
  const markdown = editorView.state.doc.toString()
  const ranges: Array<{ start: number; end: number; decoration: Decoration }> = []
  for (const embed of journalEmbedRanges(markdown)) {
    if (embed.kind !== 'media') continue
    const mediaIds = embed.mediaIds ?? []
    if (mediaIds.length === 0) continue
    ranges.push({
      start: embed.start,
      end: embed.end,
      decoration: Decoration.replace({
        widget: new MediaTagEditWidget(
          {
            start: embed.start,
            end: embed.end,
            caption: embed.label,
            mediaIds
          },
          `::media:${countLabel(mediaIds.length)}`,
          label,
          onEdit
        ),
        side: 1
      })
    })
  }
  for (const embed of journalEmbedRanges(markdown)) {
    if (embed.kind === 'media') continue
    const kind = embed.kind
    const value = embed.targetId?.trim() ?? ''
    if (!value) continue
    ranges.push({
      start: embed.start,
      end: embed.end,
      decoration: Decoration.replace({
        widget: new EmbedChipWidget(
          {
            start: embed.start,
            end: embed.end,
            kind,
            label: embed.label,
            targetId: value
          },
          journalEmbedMarkdown(kind, [embedLabel(kind, value, embed.label || '')]),
          label,
          onEmbedEdit
        )
      })
    })
  }
  ranges
    .sort((left, right) => left.start - right.start || left.end - right.end)
    .forEach((range) => builder.add(range.start, range.end, range.decoration))
  return builder.finish()
}

function embedChipLabel(kind: JournalEmbedKind, value: string, label: string): string {
  if (kind === 'chapter') {
    return props.childMemories.find((memory) => memory.id === value)?.displayName || label || value
  }
  if (kind === 'playlist') {
    return props.playlists?.find((playlist) => playlist.id === value)?.title || label || value
  }
  return props.tracks?.find((track) => track.id === value)?.title || label || value
}

watch(
  () => props.modelValue,
  (value) => {
    if (localUpdate || !view || view.state.doc.toString() === value) return
    view.dispatch({ changes: { from: 0, to: view.state.doc.length, insert: value } })
  }
)

defineExpose({ insertMarkdown, currentMediaTagRange, currentJournalEmbedRange })
</script>

<template>
  <div class="memory-journal-live-editor">
    <div class="memory-journal-live-header">
      <span class="memory-journal-live-status" :data-status="status">{{ statusLabel }}</span>
      <span
        v-for="collaborator in collaborators"
        :key="collaborator.clientId"
        class="memory-journal-live-presence"
        :style="{ '--presence-color': collaborator.color }"
      >
        {{ collaborator.name }}
      </span>
    </div>
    <div ref="editorHost" class="memory-journal-codemirror" />
    <div class="memory-journal-preview">
      <JournalRenderer
        v-if="modelValue.trim()"
        :markdown="modelValue"
        :media="media"
        :child-memories="childMemories"
        :playlists="playlists"
        :tracks="tracks"
        @open-memory="$emit('openMemory', $event)"
      />
      <p v-else class="veyq-empty">{{ t('memory.journalEmptyPreview') }}</p>
    </div>
  </div>
</template>
