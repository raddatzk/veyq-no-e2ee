<script setup lang="ts">
// Memory detail on the E2EE backend: media gallery (+ upload), GPX tracks/map, and the journal (read or
// live co-edit). Plus title rename and child chapters (sub-memories). All names/content are sealed under the
// scope content key; this view threads the active space + node id through the data-layer modules.
import { onMounted, ref, shallowRef } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import ProgressSpinner from 'primevue/progressspinner'
import JournalContent from '../features/memories/components/JournalContent.vue'
import JournalLiveEditor from '../features/memories/components/JournalLiveEditor.vue'
import MemoryGallery from '../features/memories/components/MemoryGallery.vue'
import MemoryTracks from '../features/memories/components/MemoryTracks.vue'
import NodeCover from '../components/NodeCover.vue'
import { ensureActiveSpaceForRoute } from '../app/spaces'
import { loadLevel, loadMemoryById, type ActiveSpace, type LevelEntry } from '../features/home/home'
import { getJournal } from '../features/memory/journal/journal'
import { renameNode, createChild } from '../features/memory/curate/curate'
import type { MemoryNode } from '../features/memory/createTree/memoryTree'
import { requireSession, session } from '../app/session'

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
const node = shallowRef<MemoryNode | null>(null)
const children = ref<LevelEntry[]>([])

const renameOpen = ref(false)
const renameValue = ref('')
const chapterName = ref('')
const busy = ref(false)

onMounted(load)

async function load() {
  loading.value = true
  error.value = ''
  try {
    active.value = await ensureActiveSpaceForRoute(null)
    const memory = await loadMemoryById(session.apiBase, requireSession(), active.value, nodeId)
    node.value = memory?.node ?? null
    title.value = memory?.name ?? t('journalView.title')
    children.value = await loadLevel(session.apiBase, requireSession(), active.value, nodeId)
    markdown.value = (await getJournal(session.apiBase, requireSession(), active.value, nodeId)).markdown
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : t('journalView.loadFailed')
  } finally {
    loading.value = false
  }
}

function openRename() {
  renameValue.value = title.value
  renameOpen.value = true
}

async function doRename() {
  if (!active.value || !node.value || !renameValue.value.trim()) return
  busy.value = true
  error.value = ''
  try {
    node.value = await renameNode(session.apiBase, requireSession(), active.value, node.value, renameValue.value.trim())
    title.value = renameValue.value.trim()
    renameOpen.value = false
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : t('memoryDetail.renameFailed')
  } finally {
    busy.value = false
  }
}

async function addChapter() {
  if (!active.value || !chapterName.value.trim()) return
  busy.value = true
  error.value = ''
  try {
    await createChild(session.apiBase, requireSession(), active.value, 'CHAPTER', chapterName.value.trim(), children.value.length, nodeId)
    chapterName.value = ''
    children.value = await loadLevel(session.apiBase, requireSession(), active.value, nodeId)
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : t('memoryDetail.addChapterFailed')
  } finally {
    busy.value = false
  }
}

function onSaved(md: string) {
  markdown.value = md
}
</script>

<template>
  <div class="veyq-page veyq-page-narrow">
    <NodeCover v-if="active && node" :active="active" :node="node" class="detail-cover" icon="pi pi-image" />
    <div class="journal-head">
      <Button type="button" icon="pi pi-arrow-left" text @click="router.push('/')" />
      <h1>{{ title }}</h1>
      <Button v-if="active && node" type="button" icon="pi pi-pencil" :aria-label="t('memoryDetail.rename')" text @click="openRename" />
      <Button
        v-if="active"
        type="button"
        class="journal-mode-toggle"
        :icon="editing ? 'pi pi-eye' : 'pi pi-file-edit'"
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
      <MemoryGallery :active="active" :node-id="nodeId" />
      <MemoryTracks :active="active" :node-id="nodeId" />

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

      <!-- Child chapters (sub-memories) -->
      <section class="chapters">
        <div class="settings-section-header"><h2>{{ t('memoryDetail.chapters') }}</h2></div>
        <ul v-if="children.length" class="chapter-list">
          <li v-for="child in children" :key="child.node.id">
            <button type="button" class="chapter-item" @click="router.push(`/m/${child.node.id}`)">
              <NodeCover v-if="active" :active="active" :node="child.node" class="chapter-cover" />
              <span>{{ child.name }}</span><i class="pi pi-angle-right chapter-go" />
            </button>
          </li>
        </ul>
        <form class="chapter-add" @submit.prevent="addChapter">
          <InputText v-model="chapterName" :placeholder="t('memoryDetail.addChapterName')" />
          <Button type="submit" :label="t('memoryDetail.addChapter')" icon="pi pi-plus" :loading="busy" :disabled="!chapterName.trim()" />
        </form>
      </section>
    </template>

    <Dialog v-model:visible="renameOpen" :header="t('memoryDetail.renameTitle')" modal class="tenant-dialog">
      <form class="veyq-form" @submit.prevent="doRename">
        <label>{{ t('common.name') }}<InputText v-model="renameValue" autofocus /></label>
        <div class="veyq-dialog-action-row">
          <Button type="button" :label="t('common.cancel')" severity="secondary" text @click="renameOpen = false" />
          <Button type="submit" :label="t('common.save')" icon="pi pi-save" :loading="busy" :disabled="!renameValue.trim()" />
        </div>
      </form>
    </Dialog>
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
.chapters {
  margin-top: 1.5rem;
}
.chapter-list {
  list-style: none;
  margin: 0 0 0.75rem;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 0.4rem;
}
.chapter-item {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 0.6rem;
  padding: 0.6rem 0.9rem;
  border: 1px solid var(--p-content-border-color, rgba(127, 127, 127, 0.2));
  border-radius: 10px;
  background: transparent;
  color: inherit;
  cursor: pointer;
  text-align: left;
}
.chapter-item:hover { border-color: var(--p-primary-color, #5b4df5); }
.chapter-go { margin-left: auto; opacity: 0.5; }
.chapter-add { display: flex; gap: 0.5rem; }
.chapter-cover { width: 2rem; height: 2rem; border-radius: 6px; flex: 0 0 auto; }
.detail-cover {
  display: block;
  width: 100%;
  height: 200px;
  border-radius: 12px;
  margin-bottom: 1rem;
}
</style>
