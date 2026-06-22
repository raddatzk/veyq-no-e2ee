<script setup lang="ts">
// Read or live-edit a memory's journal on the E2EE backend. Read mode decrypts the sealed Markdown
// projection (getJournal) and renders it with the existing JournalRenderer; edit mode opens the operator-
// blind collaborative editor (Yjs over the sealed relay + durable update log). Media/track embeds are a
// later increment — for now an embed token renders as plain text.
import { onMounted, ref, shallowRef } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Message from 'primevue/message'
import ProgressSpinner from 'primevue/progressspinner'
import JournalContent from '../features/memories/components/JournalContent.vue'
import JournalLiveEditor from '../features/memories/components/JournalLiveEditor.vue'
import { ensureActiveSpaceForRoute } from '../app/spaces'
import { loadMemoryById } from '../features/home/home'
import { getJournal } from '../features/memory/journal/journal'
import { requireSession, session } from '../app/session'
import type { ActiveSpace } from '../features/home/home'

const route = useRoute()
const router = useRouter()
const { t } = useI18n()

const nodeId = String(route.params.nodeId)
const title = ref('')
const markdown = ref('')
const loading = ref(false)
const error = ref('')
const editing = ref(false)
const active = shallowRef<ActiveSpace | null>(null)

onMounted(load)

async function load() {
  loading.value = true
  error.value = ''
  try {
    active.value = await ensureActiveSpaceForRoute(null)
    const memory = await loadMemoryById(session.apiBase, requireSession(), active.value, nodeId)
    title.value = memory?.name ?? t('journalView.title')
    markdown.value = (await getJournal(session.apiBase, requireSession(), active.value, nodeId)).markdown
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : t('journalView.loadFailed')
  } finally {
    loading.value = false
  }
}

function onSaved(md: string) {
  markdown.value = md
}
</script>

<template>
  <div class="veyq-page veyq-page-narrow">
    <div class="journal-head">
      <Button type="button" icon="pi pi-arrow-left" text @click="router.push('/')" />
      <h1>{{ title }}</h1>
      <Button
        v-if="active"
        type="button"
        class="journal-mode-toggle"
        :icon="editing ? 'pi pi-eye' : 'pi pi-pencil'"
        :label="editing ? t('journalView.read') : t('journalView.edit')"
        severity="secondary"
        text
        @click="editing = !editing"
      />
    </div>

    <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>

    <div v-if="loading" class="veyq-loading">
      <ProgressSpinner class="small-spinner" />
      <span>{{ t('common.loading') }}</span>
    </div>

    <template v-else-if="editing && active">
      <JournalLiveEditor
        :api-base="session.apiBase"
        :session="requireSession()"
        :scope-id="active.scope.scopeId"
        :node-id="nodeId"
        :content-key="active.contentKey"
        @saved="onSaved"
      />
    </template>

    <template v-else-if="active">
      <Message v-if="!markdown.trim()" severity="info" :closable="false">{{ t('journalView.empty') }}</Message>
      <JournalContent
        v-else
        :api-base="session.apiBase"
        :session="requireSession()"
        :scope-id="active.scope.scopeId"
        :node-id="nodeId"
        :content-key="active.contentKey"
        :markdown="markdown"
      />
    </template>
  </div>
</template>

<style scoped>
.journal-head {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  margin-bottom: 1rem;
}
.journal-mode-toggle {
  margin-left: auto;
}
</style>
