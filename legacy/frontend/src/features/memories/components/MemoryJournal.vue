<script setup lang="ts">
// A memory's journal. Two paths over the SAME operator-blind CRDT store (sealed snapshot + sealed update log):
//  - read/single-writer Markdown: getJournal (read projection) + saveJournalMarkdown (compaction).
//  - LIVE multi-cursor co-editing: MemoryJournalLive (Yjs over the encrypted relay + CodeMirror cursors).
// Both converge; the server never sees plaintext.
import { ref, watch } from 'vue'
import Button from 'primevue/button'
import ProgressSpinner from 'primevue/progressspinner'
import { getJournal } from '../../../features/memory/journal/journal'
import { renderMarkdown } from '../../../features/memory/journal/markdown'
import { saveJournalMarkdown } from '../../../features/memory/journalLive/journalPersistence'
import type { ActiveSpace } from '../../../features/home/home'
import { session } from '../../../app/session'
import { t } from '../../../i18n/i18n'
import MemoryJournalLive from './MemoryJournalLive.vue'

const props = defineProps<{ active: ActiveSpace; nodeId: string }>()

const markdown = ref('')
const draft = ref('')
const editing = ref(false)
const live = ref(false)
const loading = ref(false)
const saving = ref(false)
const error = ref('')

function onLiveClose(finalMarkdown: string) {
  // Show the just-edited text immediately — the read projection is compacted asynchronously on close, so a
  // bare reload would briefly race the compaction and show stale content. Then re-sync in the background.
  markdown.value = finalMarkdown
  live.value = false
  void load()
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    markdown.value = (await getJournal(session.apiBase, session.unlocked!, props.active, props.nodeId)).markdown
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

function startEdit() {
  draft.value = markdown.value
  editing.value = true
}

async function save() {
  saving.value = true
  error.value = ''
  try {
    await saveJournalMarkdown(session.apiBase, session.unlocked!, props.active.scope.scopeId, props.nodeId, props.active.contentKey, draft.value)
    markdown.value = draft.value
    editing.value = false
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    saving.value = false
  }
}

watch(() => [props.active.scope.scopeId, props.nodeId], () => { editing.value = false; live.value = false; void load() }, { immediate: true })
</script>

<template>
  <div class="memory-journal">
    <p v-if="error" class="journal-error">{{ error }}</p>
    <div v-if="loading" class="veyq-loading"><ProgressSpinner class="small-spinner" /></div>

    <MemoryJournalLive
      v-else-if="live"
      :active="props.active"
      :node-id="props.nodeId"
      :initial-markdown="markdown"
      @close="onLiveClose"
    />

    <template v-else-if="editing">
      <textarea v-model="draft" class="journal-editor" :placeholder="t('journal.placeholder')" rows="16"></textarea>
      <div class="journal-actions">
        <Button type="button" :label="saving ? t('journal.saving') : t('journal.save')" icon="pi pi-check" :loading="saving" @click="save" />
        <Button type="button" :label="t('common.cancel')" severity="secondary" text :disabled="saving" @click="editing = false" />
      </div>
    </template>

    <template v-else>
      <div class="journal-toolbar">
        <Button type="button" :label="t('journal.edit')" icon="pi pi-pencil" severity="secondary" outlined @click="startEdit" />
        <Button type="button" :label="t('journal.live')" icon="pi pi-users" severity="secondary" outlined @click="live = true" />
      </div>
      <!-- renderMarkdown HTML-escapes input first (safe subset); no raw HTML passes through. -->
      <!-- eslint-disable-next-line vue/no-v-html -->
      <div v-if="markdown" class="journal-body" v-html="renderMarkdown(markdown)"></div>
      <p v-else class="empty-hint">{{ t('journal.empty') }}</p>
    </template>
  </div>
</template>

<style scoped>
.memory-journal { margin: 0.5rem 0 1rem; }
.journal-error { color: var(--p-red-400, #f87171); }
.journal-toolbar { display: flex; gap: 0.5rem; margin-bottom: 0.75rem; }
.journal-actions { display: flex; gap: 0.5rem; margin-top: 0.5rem; }
.journal-editor { width: 100%; box-sizing: border-box; font-family: var(--font-mono, monospace); font-size: 0.95rem; padding: 0.75rem; border-radius: 8px; border: 1px solid var(--p-content-border-color, rgba(127,127,127,0.3)); background: var(--p-content-background, transparent); color: inherit; resize: vertical; }
.journal-body { line-height: 1.6; }
.journal-body :deep(h1), .journal-body :deep(h2), .journal-body :deep(h3) { margin: 1rem 0 0.5rem; }
.journal-body :deep(p) { margin: 0.5rem 0; }
.empty-hint { opacity: 0.6; }
</style>
