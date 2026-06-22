<script setup lang="ts">
// Memory detail on the E2EE backend: media gallery (+ upload), GPX tracks/map, and the journal (read or
// live co-edit). Plus title rename and child chapters (sub-memories). All names/content are sealed under the
// scope content key; this view threads the active space + node id through the data-layer modules.
import { computed, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import ProgressSpinner from 'primevue/progressspinner'
import Tabs from 'primevue/tabs'
import TabList from 'primevue/tablist'
import Tab from 'primevue/tab'
import TabPanels from 'primevue/tabpanels'
import TabPanel from 'primevue/tabpanel'
import JournalContent from '../features/memories/components/JournalContent.vue'
import JournalLiveEditor from '../features/memories/components/JournalLiveEditor.vue'
import MemoryGallery from '../features/memories/components/MemoryGallery.vue'
import MemoryTracks from '../features/memories/components/MemoryTracks.vue'
import MemoryPlaylists from '../features/memories/components/MemoryPlaylists.vue'
import MemoryContributions from '../features/memories/components/MemoryContributions.vue'
import MemoryInfo from '../features/memories/components/MemoryInfo.vue'
import MemoryPlaylistBar from '../features/memories/components/MemoryPlaylistBar.vue'
import MemoryShareDialog from '../features/memories/components/MemoryShareDialog.vue'
import CoverPickerDialog from '../features/memories/components/CoverPickerDialog.vue'
import UploaderShareDialog from '../features/memories/components/UploaderShareDialog.vue'
import NodeCover from '../components/NodeCover.vue'
import { ensureActiveSpaceForRoute } from '../app/spaces'
import { ancestorChain, loadLevel, loadMemoryById, type ActiveSpace, type LevelEntry } from '../features/home/home'
import { setWorkspaceToolbar, clearWorkspaceToolbar } from '../composables/useWorkspaceToolbar'
import { getJournal } from '../features/memory/journal/journal'
import { listPlaylists, type Playlist } from '../features/memory/playlist/playlist'
import { renameNode, createChild, deleteNode } from '../features/memory/curate/curate'
import { listFavoriteNodeIds, setMemoryFavorite } from '../features/memory/favorite/favorite'
import type { MemoryNode } from '../features/memory/createTree/memoryTree'
import { memoryTabFromHash, memoryTabToHash, type MemoryDetailTab } from '../features/memories/utils/memoryTabs'
import { uploadMedia } from '../features/uploads/media/media'
import { fileToNewMedia } from '../features/uploads/clientMedia'
import { enqueueUploads } from '../features/uploads/useUploadQueue'
import { requireSession, session } from '../app/session'

const route = useRoute()
const router = useRouter()
const { t } = useI18n()

// Reactive so navigating between memories/chapters (same route name, param change) reloads the view.
const nodeId = computed(() => String(route.params.nodeId))
const title = ref('')
const markdown = ref('')
const loading = ref(false)
const error = ref('')
const editing = ref(false)
const active = shallowRef<ActiveSpace | null>(null)
const node = shallowRef<MemoryNode | null>(null)
const children = ref<LevelEntry[]>([])
const playlists = ref<Playlist[]>([])

const renameOpen = ref(false)
const renameValue = ref('')
const chapterName = ref('')
const shareOpen = ref(false)
const uploaderOpen = ref(false)
const coverOpen = ref(false)
const deleteOpen = ref(false)
const favorite = ref(false)
const favBusy = ref(false)
const busy = ref(false)
const uploadInput = ref<HTMLInputElement | null>(null)
const uploading = ref(false)
const galleryKey = ref(0) // bumped after an upload to remount the gallery and reload its tiles

// Active tab, synced to the URL hash (#journal, #map, …) like the old memory detail.
const selectedTab = ref<MemoryDetailTab>(memoryTabFromHash(route.hash))
watch(selectedTab, (tab) => { void router.replace({ params: route.params, hash: memoryTabToHash(tab) }) })
watch(() => route.hash, (h) => { selectedTab.value = memoryTabFromHash(h) })

onMounted(load)
onBeforeUnmount(clearWorkspaceToolbar)
// Reload when navigating to another memory/chapter (the component is reused across /m/:nodeId).
watch(nodeId, () => { editing.value = false; void load() })

async function load() {
  loading.value = true
  error.value = ''
  try {
    active.value = await ensureActiveSpaceForRoute(null)
    const memory = await loadMemoryById(session.apiBase, requireSession(), active.value, nodeId.value)
    node.value = memory?.node ?? null
    title.value = memory?.name ?? t('journalView.title')
    children.value = await loadLevel(session.apiBase, requireSession(), active.value, nodeId.value)
    await setBreadcrumbs()
    void reloadPlaylists()
    void reloadFavorite()
    markdown.value = (await getJournal(session.apiBase, requireSession(), active.value, nodeId.value)).markdown
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : t('journalView.loadFailed')
  } finally {
    loading.value = false
  }
}

/** Featured playlist(s) drive the header chip; reload after edits in the Playlists tab. */
async function reloadPlaylists() {
  if (!active.value) return
  try {
    playlists.value = await listPlaylists(session.apiBase, requireSession(), active.value, nodeId.value)
  } catch {
    playlists.value = []
  }
}

/** Reflect whether this memory is in the active scope's favourites (drives the star toggle). */
async function reloadFavorite() {
  if (!active.value) return
  try {
    const ids = await listFavoriteNodeIds(session.apiBase, requireSession(), active.value.scope.scopeId)
    favorite.value = ids.includes(nodeId.value)
  } catch {
    favorite.value = false
  }
}

async function toggleFavorite() {
  if (!active.value || favBusy.value) return
  favBusy.value = true
  try {
    favorite.value = await setMemoryFavorite(session.apiBase, requireSession(), active.value.scope.scopeId, nodeId.value, !favorite.value)
  } catch {
    /* keep the previous state on failure */
  } finally {
    favBusy.value = false
  }
}

/** Delete this memory (and its subtree), then navigate to the parent or home. */
async function doDelete() {
  if (!active.value || !node.value) return
  busy.value = true
  error.value = ''
  try {
    const parentId = node.value.parentId
    await deleteNode(session.apiBase, requireSession(), active.value, nodeId.value)
    deleteOpen.value = false
    await router.push(parentId ? `/m/${parentId}` : '/')
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : t('memory.deleteFailed')
  } finally {
    busy.value = false
  }
}

function onCoverSaved(coverMediaId: string) {
  if (node.value) node.value = { ...node.value, coverMediaId }
}

/** Build the breadcrumb trail client-side from the decrypted ancestor chain (root → … → this memory). */
async function setBreadcrumbs() {
  if (!active.value) return
  const rootLabel = active.value.entry.kind === 'PERSONAL' ? t('homeView.title') : active.value.entry.displayName
  const chain = await ancestorChain(session.apiBase, requireSession(), active.value, nodeId.value)
  setWorkspaceToolbar(
    [
      { label: rootLabel, command: () => router.push('/') },
      ...chain.map((entry) => ({ label: entry.name, command: () => router.push(`/m/${entry.node.id}`) })),
    ],
    [],
  )
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
    await createChild(session.apiBase, requireSession(), active.value, 'CHAPTER', chapterName.value.trim(), children.value.length, nodeId.value)
    chapterName.value = ''
    children.value = await loadLevel(session.apiBase, requireSession(), active.value, nodeId.value)
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : t('memoryDetail.addChapterFailed')
  } finally {
    busy.value = false
  }
}

function onSaved(md: string) {
  markdown.value = md
}

async function onUpload(event: Event) {
  const input = event.target as HTMLInputElement
  const files = Array.from(input.files ?? [])
  input.value = ''
  if (files.length === 0 || !active.value) return
  uploading.value = true
  error.value = ''
  try {
    const space = active.value
    await enqueueUploads(files, async (file) => {
      const media = await fileToNewMedia(file)
      await uploadMedia(session.apiBase, requireSession(), space.scope.scopeId, space.contentKey, nodeId.value, media)
    })
    galleryKey.value += 1 // reload the gallery
  } catch {
    error.value = t('gallery.uploadFailed')
  } finally {
    uploading.value = false
  }
}
</script>

<template>
  <div class="veyq-page veyq-page-narrow">
    <NodeCover v-if="active && node" :active="active" :node="node" class="detail-cover" icon="pi pi-image" />
    <header class="memory-detail-header">
      <h1>{{ title }}</h1>
      <div v-if="active" class="memory-detail-actions">
        <Button
          type="button"
          class="veyq-icon-button"
          :icon="favorite ? 'pi pi-star-fill' : 'pi pi-star'"
          :aria-label="favorite ? t('memory.favoriteRemove') : t('memory.favoriteAdd')"
          :title="favorite ? t('memory.favoriteRemove') : t('memory.favoriteAdd')"
          :loading="favBusy"
          text
          @click="toggleFavorite"
        />
        <Button
          type="button"
          class="veyq-icon-button"
          icon="pi pi-upload"
          :aria-label="t('gallery.upload')"
          :title="t('gallery.upload')"
          :loading="uploading"
          text
          @click="uploadInput?.click()"
        />
        <MemoryPlaylistBar v-if="playlists.length" :playlists="playlists" />
        <Button type="button" class="veyq-icon-button" icon="pi pi-image" :aria-label="t('memory.coverChoose')" :title="t('memory.coverChoose')" text @click="coverOpen = true" />
        <Button type="button" class="veyq-icon-button" icon="pi pi-share-alt" :aria-label="t('share.title')" :title="t('share.title')" text @click="shareOpen = true" />
        <Button type="button" class="veyq-icon-button" icon="pi pi-user-plus" :aria-label="t('share.uploaderTitle')" :title="t('share.uploaderTitle')" text @click="uploaderOpen = true" />
        <Button v-if="node" type="button" class="veyq-icon-button" icon="pi pi-pencil" :aria-label="t('memoryDetail.rename')" :title="t('memoryDetail.rename')" text @click="openRename" />
        <Button v-if="node" type="button" class="veyq-icon-button" icon="pi pi-trash" severity="danger" :aria-label="t('memory.delete')" :title="t('memory.delete')" text @click="deleteOpen = true" />
        <input ref="uploadInput" class="kit-file-input" type="file" accept="image/*,video/*" multiple hidden @change="onUpload" />
      </div>
    </header>

    <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>

    <div v-if="loading" class="veyq-loading">
      <ProgressSpinner class="small-spinner" />
      <span>{{ t('common.loading') }}</span>
    </div>

    <Tabs v-else-if="active" v-model:value="selectedTab" class="memory-tabs">
      <TabList>
        <Tab value="gallery">{{ t('memory.gallery') }}</Tab>
        <Tab value="map">{{ t('memory.context') }}</Tab>
        <Tab value="playlists">{{ t('memoryDetail.playlists') }}</Tab>
        <Tab value="journal">{{ t('memory.journal') }}</Tab>
        <Tab value="children">{{ t('memory.children') }}</Tab>
        <Tab value="contributions">{{ t('media.contributions') }}</Tab>
        <Tab value="info">{{ t('memory.info') }}</Tab>
      </TabList>
      <TabPanels>
        <TabPanel value="gallery">
          <MemoryGallery :key="`g-${nodeId}-${galleryKey}`" :active="active" :node-id="nodeId" />
        </TabPanel>

        <TabPanel value="map">
          <MemoryTracks :key="`t-${nodeId}`" :active="active" :node-id="nodeId" />
        </TabPanel>

        <TabPanel value="playlists">
          <MemoryPlaylists :key="`p-${nodeId}`" :active="active" :node-id="nodeId" @changed="reloadPlaylists" />
        </TabPanel>

        <TabPanel value="journal">
          <div class="journal-section-head">
            <Button
              type="button"
              :icon="editing ? 'pi pi-eye' : 'pi pi-file-edit'"
              :label="editing ? t('journalView.read') : t('journalView.edit')"
              severity="secondary"
              text
              @click="editing = !editing"
            />
          </div>
          <JournalLiveEditor
            v-if="editing"
            :key="nodeId"
            :api-base="session.apiBase"
            :session="requireSession()"
            :scope-id="active.scope.scopeId"
            :node-id="nodeId"
            :content-key="active.contentKey"
            @saved="onSaved"
          />
          <Message v-else-if="!markdown.trim()" severity="info" :closable="false">{{ t('journalView.empty') }}</Message>
          <JournalContent
            v-else
            :key="`j-${nodeId}`"
            :api-base="session.apiBase"
            :session="requireSession()"
            :scope-id="active.scope.scopeId"
            :node-id="nodeId"
            :content-key="active.contentKey"
            :markdown="markdown"
          />
        </TabPanel>

        <TabPanel value="children">
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
        </TabPanel>

        <TabPanel value="contributions">
          <MemoryContributions :key="`c-${nodeId}-${galleryKey}`" :active="active" :node-id="nodeId" />
        </TabPanel>

        <TabPanel value="info">
          <MemoryInfo
            :key="`i-${nodeId}-${galleryKey}`"
            :active="active"
            :node-id="nodeId"
            :node="node"
            :children-count="children.length"
            :playlist-count="playlists.length"
          />
        </TabPanel>
      </TabPanels>
    </Tabs>

    <MemoryShareDialog v-if="active" v-model:visible="shareOpen" :active="active" :node-id="nodeId" />

    <UploaderShareDialog v-if="active" v-model:visible="uploaderOpen" :active="active" :node-id="nodeId" />

    <CoverPickerDialog
      v-if="active"
      v-model:visible="coverOpen"
      :active="active"
      :node-id="nodeId"
      :cover-media-id="node?.coverMediaId"
      @saved="onCoverSaved"
    />

    <Dialog v-model:visible="deleteOpen" :header="t('memory.delete')" modal class="tenant-dialog">
      <p>{{ t('memory.deleteCopy', { name: title }) }}</p>
      <div class="veyq-dialog-action-row">
        <Button type="button" :label="t('common.cancel')" severity="secondary" text @click="deleteOpen = false" />
        <Button type="button" :label="t('memory.delete')" icon="pi pi-trash" severity="danger" :loading="busy" @click="doDelete" />
      </div>
    </Dialog>

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
.memory-journal-section {
  margin-top: 1.5rem;
}
.journal-section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
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
