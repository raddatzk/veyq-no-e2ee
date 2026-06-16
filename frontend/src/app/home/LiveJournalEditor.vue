<script setup lang="ts">
// Live, operator-blind collaborative journal editing: a Yjs CRDT over the encrypted relay, bound to a
// CodeMirror editor with other members' live cursors (y-codemirror.next). The doc seeds from the existing
// sealed journal; edits are debounced-sealed back via the normal journal save. Heavy deps (yjs/codemirror)
// load only when this component mounts (the parent async-imports it).
import { onBeforeUnmount, onMounted, ref, shallowRef } from 'vue'
import * as Y from 'yjs'
import { EditorState } from '@codemirror/state'
import { EditorView, keymap, lineNumbers } from '@codemirror/view'
import { defaultKeymap, history, historyKeymap } from '@codemirror/commands'
import { markdown } from '@codemirror/lang-markdown'
import { yCollab } from 'y-codemirror.next'
import { session } from '../session'
import { t } from '../../i18n/i18n'
import { updateJournal } from '../../features/memory/journal/journal'
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
let saveTimer: number | undefined
let saving = false

// A stable, readable colour per editing session (for the live cursor + name tag).
const COLORS = ['#5b4df5', '#0ea5e9', '#10b981', '#f59e0b', '#ef4444', '#ec4899', '#8b5cf6']
const color = COLORS[Math.floor(Math.random() * COLORS.length)]

function scheduleSave() {
  window.clearTimeout(saveTimer)
  saveTimer = window.setTimeout(saveSnapshot, 1500)
}
async function saveSnapshot() {
  if (saving) return
  saving = true
  try {
    await updateJournal(session.apiBase, session.unlocked!, props.active, props.nodeId, ytext.toString())
  } catch { /* transient — the next edit retries */ } finally {
    saving = false
  }
}

onMounted(() => {
  const wsBase = session.apiBase.replace(/^http/, 'ws').replace(/\/$/, '')
  const url = `${wsBase}/ws/journal/${props.active.scope.scopeId}/${props.nodeId}?token=${encodeURIComponent(session.unlocked!.sessionToken)}`
  const p = new EncryptedRelayProvider(url, doc, props.active.contentKey)
  provider.value = p
  p.awareness.setLocalStateField('user', { name: session.unlocked!.user.displayName, color, colorLight: color + '33' })
  p.onStatus = (c) => { connected.value = c }
  p.awareness.on('change', () => { peers.value = Math.max(0, p.awareness.getStates().size - 1) })
  // Seed the empty shared doc from the sealed journal once we've synced with any existing peers.
  p.onSynced = () => {
    if (ytext.length === 0 && props.initialMarkdown.length > 0) doc.transact(() => ytext.insert(0, props.initialMarkdown))
  }
  // also seed if we connect alone (no peers reply) — give sync a moment, then seed if still empty
  window.setTimeout(() => {
    if (ytext.length === 0 && props.initialMarkdown.length > 0) doc.transact(() => ytext.insert(0, props.initialMarkdown))
  }, 800)

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
  ytext.observe(scheduleSave)
})

onBeforeUnmount(async () => {
  window.clearTimeout(saveTimer)
  ytext.unobserve(scheduleSave)
  await saveSnapshot()
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
