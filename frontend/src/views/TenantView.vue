<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import ProgressSpinner from 'primevue/progressspinner'
import { loadCover, loadLevel, moveDestinations, type ActiveSpace, type LevelEntry } from '../features/home/home'
import { createChild, deleteNode, isNodeNameConflict, moveNode, renameNode } from '../features/memory/curate/curate'
import type { MemoryNode } from '../features/memory/createTree/memoryTree'
import { buildHomePath } from '../features/home/homeRoute'
import { listFavoriteNodeIds, setMemoryFavorite } from '../features/memory/favorite/favorite'
import { downloadMemoryZip } from '../features/download/download'
import { session } from '../app/session'
import { useViewState } from '../shared/state/useViewState'
import { t } from '../i18n/i18n'
import { decodeNode, type NodeType } from '../features/memory/createTree/memoryTree'
import MemoryGallery from '../features/memories/components/MemoryGallery.vue'
import MemoryJournal from '../features/memories/components/MemoryJournal.vue'
import MemoryParticipants from '../features/memories/components/MemoryParticipants.vue'
import MemoryPlaylists from '../features/memories/components/MemoryPlaylists.vue'
import MemoryMap from '../features/memories/components/MemoryMap.vue'
import MemoryShare from '../features/memories/components/MemoryShare.vue'
import MemoryLevelGrid from '../app/home/MemoryLevelGrid.vue'
import MemoryObjectCard from '../app/home/MemoryObjectCard.vue'
import CreateNodeCard from '../app/home/CreateNodeCard.vue'
import FolderCard from '../app/home/FolderCard.vue'

const props = defineProps<{
  active: ActiveSpace
  folders: LevelEntry[]
  memory: LevelEntry | null
}>()

const router = useRouter()
const { compactView } = useViewState()
const entries = ref<LevelEntry[]>([])
const covers = ref<Record<string, string>>({})
const loading = ref(false)
const error = ref('')
const memoryTab = ref<'gallery' | 'journal' | 'map' | 'participants' | 'playlists' | 'share'>('gallery')
const createOpen = ref(false)
const createType = ref<NodeType>('MEMORY')
const createName = ref('')
const createError = ref('')
const creating = ref(false)
const createTitle = computed(() =>
  createType.value === 'FOLDER' ? t('home.createFolder') : createType.value === 'CHAPTER' ? t('detail.chapters') : t('home.createMemory'),
)
const favoriteIds = ref<Set<string>>(new Set())
const favBusy = ref(false)
const downloading = ref(false)
let coverUrls: string[] = []

// Favorites are scope-wide: load the id set once per scope so both the grid cards and the open memory's
// toolbar can reflect/toggle favourite state from the same source.
const isFavorite = computed(() => (props.memory ? favoriteIds.value.has(props.memory.node.id) : false))

async function loadFavorites() {
  try {
    const ids = await listFavoriteNodeIds(session.apiBase, session.unlocked!, props.active.scope.scopeId)
    favoriteIds.value = new Set(ids)
  } catch {
    favoriteIds.value = new Set()
  }
}

async function toggleFavorite(node: MemoryNode) {
  favBusy.value = true
  try {
    const now = await setMemoryFavorite(session.apiBase, session.unlocked!, props.active.scope.scopeId, node.id, !favoriteIds.value.has(node.id))
    const next = new Set(favoriteIds.value)
    if (now) next.add(node.id)
    else next.delete(node.id)
    favoriteIds.value = next
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    favBusy.value = false
  }
}

async function downloadMemory() {
  if (!props.memory) return
  downloading.value = true
  error.value = ''
  try {
    await downloadMemoryZip(session.apiBase, session.unlocked!, props.active, props.memory.node, props.memory.name, true)
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    downloading.value = false
  }
}

// The slug segments of the current location (folder chain + optional trailing memory).
const currentSegments = computed(() => [
  ...props.folders.map((f) => f.slug),
  ...(props.memory ? [props.memory.slug] : []),
])
// The level we list children of: inside a memory → its chapters; in a folder → that folder; else root.
const parentId = computed(() => props.memory?.node.id ?? props.folders.at(-1)?.node.id)
const folderEntries = computed(() => entries.value.filter((e) => e.node.type === 'FOLDER'))
const memoryEntries = computed(() => entries.value.filter((e) => e.node.type !== 'FOLDER'))

function releaseCovers() {
  coverUrls.forEach((u) => URL.revokeObjectURL(u))
  coverUrls = []
  covers.value = {}
}

async function load() {
  loading.value = true
  error.value = ''
  releaseCovers()
  try {
    entries.value = await loadLevel(session.apiBase, session.unlocked!, props.active, parentId.value)
    // Decrypt covers lazily, in the background, so the grid renders immediately.
    for (const entry of entries.value) {
      if (entry.node.type === 'FOLDER') continue
      void loadCover(session.apiBase, session.unlocked!, props.active, entry.node).then((url) => {
        if (url) {
          coverUrls.push(url)
          covers.value = { ...covers.value, [entry.node.id]: url }
        }
      })
    }
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

function open(entry: LevelEntry) {
  void router.push(buildHomePath(props.active.entry, [...currentSegments.value, entry.slug]))
}

// A ::chapter embed in the journal resolves to a child chapter slug; navigate into it under the current memory.
function openChapterFromJournal(slug: string) {
  void router.push(buildHomePath(props.active.entry, [...currentSegments.value, slug]))
}

function openCrumb(index: number) {
  // index -1 = space root; otherwise the folder at that depth.
  const slugs = index < 0 ? [] : props.folders.slice(0, index + 1).map((f) => f.slug)
  void router.push(buildHomePath(props.active.entry, slugs))
}

function openCreate(type: NodeType) {
  createType.value = type
  createName.value = ''
  createError.value = ''
  createOpen.value = true
}

async function confirmCreate() {
  const name = createName.value.trim()
  if (!name) return
  creating.value = true
  createError.value = ''
  try {
    const created = await createChild(session.apiBase, session.unlocked!, props.active, createType.value, name, entries.value.length, parentId.value)
    createOpen.value = false
    if (createType.value === 'FOLDER') {
      await load()
    } else {
      // Jump straight into the new memory/chapter (decode its slug for the URL).
      const slug = decodeNode(props.active.contentKey, created).slug
      void router.push(buildHomePath(props.active.entry, [...currentSegments.value, slug]))
    }
  } catch (e) {
    createError.value = isNodeNameConflict(e) ? t('home.nameConflict') : (e instanceof Error ? e.message : String(e))
  } finally {
    creating.value = false
  }
}

// --- node actions: rename / move / delete (current memory via toolbar, or any node via card hover) ---
const actionNode = ref<MemoryNode | null>(null)
const actionName = ref('')
const actionError = ref('')
const actionBusy = ref(false)
const renameOpen = ref(false)
const moveOpen = ref(false)
const deleteOpen = ref(false)
const moveTargets = ref<{ id: string; name: string }[]>([])
const moveDest = ref('root')

// Card "…" menu: a small action sheet over a single node (rename / move / delete).
const menuOpen = ref(false)
const menuEntry = ref<LevelEntry | null>(null)
function openMenu(entry: LevelEntry) {
  menuEntry.value = entry
  menuOpen.value = true
}
function menuAction(action: 'rename' | 'move' | 'delete') {
  const node = menuEntry.value?.node
  menuOpen.value = false
  if (!node) return
  if (action === 'rename') openRename(node)
  else if (action === 'move') void openMove(node)
  else openDelete(node)
}
function favoriteEntry(entry: LevelEntry) {
  void toggleFavorite(entry.node)
}

function nodeName(node: MemoryNode): string {
  if (props.memory?.node.id === node.id) return props.memory.name
  return entries.value.find((e) => e.node.id === node.id)?.name ?? ''
}

function openRename(node: MemoryNode) {
  actionNode.value = node
  actionName.value = nodeName(node)
  actionError.value = ''
  renameOpen.value = true
}

async function openMove(node: MemoryNode) {
  actionNode.value = node
  actionError.value = ''
  moveDest.value = 'root'
  moveTargets.value = []
  moveOpen.value = true
  try {
    moveTargets.value = await moveDestinations(session.apiBase, session.unlocked!, props.active, node)
  } catch (e) {
    actionError.value = e instanceof Error ? e.message : String(e)
  }
}

function openDelete(node: MemoryNode) {
  actionNode.value = node
  actionError.value = ''
  deleteOpen.value = true
}

async function confirmRename() {
  const node = actionNode.value
  const name = actionName.value.trim()
  if (!node || !name) return
  actionBusy.value = true
  actionError.value = ''
  try {
    const updated = await renameNode(session.apiBase, session.unlocked!, props.active, node, name)
    renameOpen.value = false
    if (props.memory?.node.id === node.id) {
      const slug = decodeNode(props.active.contentKey, updated).slug
      void router.push(buildHomePath(props.active.entry, [...props.folders.map((f) => f.slug), slug]))
    } else {
      await load()
    }
  } catch (e) {
    actionError.value = isNodeNameConflict(e) ? t('home.nameConflict') : e instanceof Error ? e.message : String(e)
  } finally {
    actionBusy.value = false
  }
}

async function confirmMove() {
  const node = actionNode.value
  if (!node) return
  actionBusy.value = true
  actionError.value = ''
  try {
    await moveNode(session.apiBase, session.unlocked!, props.active, node, moveDest.value)
    moveOpen.value = false
    // Moved away from the current level → go to the space root if it was the open memory, else reload.
    if (props.memory?.node.id === node.id) void router.push(buildHomePath(props.active.entry, []))
    else await load()
  } catch (e) {
    actionError.value = isNodeNameConflict(e) ? t('home.nameConflict') : e instanceof Error ? e.message : String(e)
  } finally {
    actionBusy.value = false
  }
}

async function confirmDelete() {
  const node = actionNode.value
  if (!node) return
  actionBusy.value = true
  actionError.value = ''
  try {
    await deleteNode(session.apiBase, session.unlocked!, props.active, node.id)
    deleteOpen.value = false
    if (props.memory?.node.id === node.id) void router.push(buildHomePath(props.active.entry, props.folders.map((f) => f.slug)))
    else await load()
  } catch (e) {
    actionError.value = e instanceof Error ? e.message : String(e)
  } finally {
    actionBusy.value = false
  }
}

watch(() => [props.active.scope.scopeId, parentId.value], load, { immediate: true })
watch(() => props.active.scope.scopeId, loadFavorites, { immediate: true })
onBeforeUnmount(releaseCovers)
</script>

<template>
  <div class="memory-browser">
    <nav class="memory-breadcrumb">
      <button type="button" class="crumb" @click="openCrumb(-1)">{{ props.active.entry.displayName }}</button>
      <template v-for="(folder, index) in props.folders" :key="folder.node.id">
        <span class="crumb-sep">/</span>
        <button type="button" class="crumb" @click="openCrumb(index)">{{ folder.name }}</button>
      </template>
      <template v-if="props.memory">
        <span class="crumb-sep">/</span>
        <span class="crumb crumb-current">{{ props.memory.name }}</span>
      </template>
    </nav>

    <div v-if="props.memory" class="memory-toolbar">
      <Button type="button" :icon="isFavorite ? 'pi pi-star-fill' : 'pi pi-star'" :label="isFavorite ? t('memory.unfavorite') : t('memory.favorite')" severity="secondary" text :loading="favBusy" @click="toggleFavorite(props.memory.node)" />
      <Button type="button" icon="pi pi-download" :label="t('detail.download')" severity="secondary" text :loading="downloading" @click="downloadMemory" />
      <Button type="button" icon="pi pi-pencil" :label="t('common.rename')" severity="secondary" text @click="openRename(props.memory.node)" />
      <Button type="button" icon="pi pi-arrows-alt" :label="t('common.move')" severity="secondary" text @click="openMove(props.memory.node)" />
      <Button type="button" icon="pi pi-trash" :label="t('common.delete')" severity="danger" text @click="openDelete(props.memory.node)" />
    </div>

    <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>

    <!-- Inside a memory: gallery (encrypted media) / journal, plus any chapters. -->
    <template v-if="props.memory">
      <div class="memory-tabs">
        <button type="button" class="memory-tab" :class="{ active: memoryTab === 'gallery' }" @click="memoryTab = 'gallery'">{{ t('detail.gallery') }}</button>
        <button type="button" class="memory-tab" :class="{ active: memoryTab === 'journal' }" @click="memoryTab = 'journal'">{{ t('detail.journal') }}</button>
        <button type="button" class="memory-tab" :class="{ active: memoryTab === 'map' }" @click="memoryTab = 'map'">{{ t('detail.map') }}</button>
        <button type="button" class="memory-tab" :class="{ active: memoryTab === 'participants' }" @click="memoryTab = 'participants'">{{ t('people.participants') }}</button>
        <button type="button" class="memory-tab" :class="{ active: memoryTab === 'playlists' }" @click="memoryTab = 'playlists'">{{ t('detail.playlists') }}</button>
        <button type="button" class="memory-tab" :class="{ active: memoryTab === 'share' }" @click="memoryTab = 'share'">{{ t('share.share') }}</button>
      </div>
      <MemoryGallery v-if="memoryTab === 'gallery'" :active="props.active" :node-id="props.memory.node.id" />
      <MemoryJournal v-else-if="memoryTab === 'journal'" :active="props.active" :node-id="props.memory.node.id" @open-chapter="openChapterFromJournal" />
      <MemoryMap v-else-if="memoryTab === 'map'" :active="props.active" :node-id="props.memory.node.id" />
      <MemoryParticipants v-else-if="memoryTab === 'participants'" :active="props.active" :node-id="props.memory.node.id" />
      <MemoryPlaylists v-else-if="memoryTab === 'playlists'" :active="props.active" :node-id="props.memory.node.id" />
      <MemoryShare v-else :active="props.active" :node-id="props.memory.node.id" />

      <!-- Chapters of this memory, rendered with the same rich cards as the browse grid. -->
      <div class="veyq-section-header content-head">
        <span class="content-title">{{ t('info.chapters') }}</span>
        <span class="content-count">{{ memoryEntries.length }}</span>
      </div>
      <div class="card-grid card-grid-memories" :class="{ 'card-grid-compact': compactView }">
        <MemoryObjectCard
          v-for="m in memoryEntries"
          :key="m.node.id"
          :name="m.name"
          :cover="covers[m.node.id]"
          :favorite="favoriteIds.has(m.node.id)"
          :favorite-label="favoriteIds.has(m.node.id) ? t('memory.unfavorite') : t('memory.favorite')"
          :menu-label="`${t('common.rename')} / ${t('common.move')} / ${t('common.delete')}`"
          @open="open(m)"
          @toggle-favorite="favoriteEntry(m)"
          @menu="openMenu(m)"
        />
        <CreateNodeCard @create="openCreate('CHAPTER')">{{ t('detail.chapters') }}</CreateNodeCard>
      </div>
      <template v-if="folderEntries.length">
        <div class="veyq-section-header content-head">
          <span class="content-title">{{ t('home.folders') }}</span>
          <span class="content-count">{{ folderEntries.length }}</span>
        </div>
        <div class="card-grid" :class="{ 'card-grid-compact': compactView }">
          <FolderCard
            v-for="f in folderEntries"
            :key="f.node.id"
            :name="f.name"
            :menu-label="`${t('common.rename')} / ${t('common.move')} / ${t('common.delete')}`"
            @open="open(f)"
            @menu="openMenu(f)"
          />
          <CreateNodeCard row @create="openCreate('FOLDER')">{{ t('home.createFolder') }}</CreateNodeCard>
        </div>
      </template>
    </template>

    <div v-if="loading" class="veyq-loading"><ProgressSpinner class="small-spinner" /></div>

    <!-- Browse level: the rich folder/memory grid with section headers + inline create tiles. -->
    <MemoryLevelGrid
      v-else-if="!props.memory"
      :memories="memoryEntries"
      :folders="folderEntries"
      :covers="covers"
      :favorite-ids="favoriteIds"
      :compact="compactView"
      @open-memory="open"
      @open-folder="open"
      @toggle-favorite="favoriteEntry"
      @create="openCreate"
      @open-menu="openMenu"
    />

    <Dialog v-model:visible="menuOpen" :header="menuEntry?.name" modal class="tenant-dialog" :style="{ width: '20rem' }">
      <div class="veyq-form">
        <Button type="button" :label="t('common.rename')" icon="pi pi-pencil" severity="secondary" text @click="menuAction('rename')" />
        <Button type="button" :label="t('common.move')" icon="pi pi-arrows-alt" severity="secondary" text @click="menuAction('move')" />
        <Button type="button" :label="t('common.delete')" icon="pi pi-trash" severity="danger" text @click="menuAction('delete')" />
      </div>
    </Dialog>

    <Dialog v-model:visible="createOpen" :header="createTitle" modal class="tenant-dialog" :style="{ width: '24rem' }">
      <form class="veyq-form" @submit.prevent="confirmCreate">
        <label>
          {{ t('common.name') }}
          <InputText v-model="createName" autofocus :disabled="creating" />
        </label>
        <Message v-if="createError" severity="error" :closable="false">{{ createError }}</Message>
        <div class="veyq-dialog-action-row">
          <Button type="button" :label="t('common.cancel')" severity="secondary" text :disabled="creating" @click="createOpen = false" />
          <Button type="submit" :label="t('common.create')" icon="pi pi-check" :loading="creating" :disabled="!createName.trim()" />
        </div>
      </form>
    </Dialog>

    <Dialog v-model:visible="renameOpen" :header="t('common.rename')" modal class="tenant-dialog" :style="{ width: '24rem' }">
      <form class="veyq-form" @submit.prevent="confirmRename">
        <label>{{ t('common.name') }}<InputText v-model="actionName" autofocus :disabled="actionBusy" /></label>
        <Message v-if="actionError" severity="error" :closable="false">{{ actionError }}</Message>
        <div class="veyq-dialog-action-row">
          <Button type="button" :label="t('common.cancel')" severity="secondary" text :disabled="actionBusy" @click="renameOpen = false" />
          <Button type="submit" :label="t('common.save')" icon="pi pi-check" :loading="actionBusy" :disabled="!actionName.trim()" />
        </div>
      </form>
    </Dialog>

    <Dialog v-model:visible="moveOpen" :header="t('move.title')" modal class="tenant-dialog" :style="{ width: '24rem' }">
      <form class="veyq-form" @submit.prevent="confirmMove">
        <label>
          {{ t('common.move') }}
          <select v-model="moveDest" class="move-select" :disabled="actionBusy">
            <option v-for="dest in moveTargets" :key="dest.id" :value="dest.id">{{ dest.name }}</option>
          </select>
        </label>
        <Message v-if="actionError" severity="error" :closable="false">{{ actionError }}</Message>
        <div class="veyq-dialog-action-row">
          <Button type="button" :label="t('common.cancel')" severity="secondary" text :disabled="actionBusy" @click="moveOpen = false" />
          <Button type="submit" :label="t('common.move')" icon="pi pi-arrows-alt" :loading="actionBusy" />
        </div>
      </form>
    </Dialog>

    <Dialog v-model:visible="deleteOpen" :header="t('delete.title')" modal class="tenant-dialog" :style="{ width: '24rem' }">
      <p class="veyq-form-copy">{{ t('delete.warning') }}</p>
      <Message v-if="actionError" severity="error" :closable="false">{{ actionError }}</Message>
      <div class="veyq-dialog-action-row">
        <Button type="button" :label="t('common.cancel')" severity="secondary" text :disabled="actionBusy" @click="deleteOpen = false" />
        <Button type="button" :label="t('delete.confirm')" icon="pi pi-trash" severity="danger" :loading="actionBusy" @click="confirmDelete" />
      </div>
    </Dialog>
  </div>
</template>

<style scoped>
/* The folder/memory grids + section headers come from the ported shell (styles/home.css: .card-grid,
   .content-head, .home-memory-card, .folder-card, .create-card). Only the breadcrumb, the in-memory
   toolbar/tabs and the move-select are local to this view. */
.memory-browser { padding: 1rem; }
.memory-breadcrumb { display: flex; flex-wrap: wrap; align-items: center; gap: 0.35rem; margin-bottom: 0.75rem; }
.crumb { background: none; border: none; color: var(--app-accent, #6366f1); cursor: pointer; padding: 0; font: inherit; }
.crumb-current { color: inherit; cursor: default; font-weight: 600; }
.crumb-sep { opacity: 0.5; }
.memory-toolbar { display: flex; flex-wrap: wrap; gap: 0.5rem; margin-bottom: 1rem; }
.move-select { width: 100%; padding: 0.5rem; border-radius: 8px; border: 1px solid var(--app-line, rgba(127,127,127,0.3)); background: var(--app-control-bg, transparent); color: inherit; }
.memory-tabs { display: flex; gap: 0.25rem; border-bottom: 1px solid var(--app-line, rgba(127,127,127,0.2)); margin-bottom: 1rem; }
.memory-tab { background: none; border: none; border-bottom: 2px solid transparent; padding: 0.5rem 0.85rem; cursor: pointer; color: inherit; opacity: 0.7; }
.memory-tab.active { opacity: 1; border-bottom-color: var(--app-accent, #6366f1); font-weight: 600; }
</style>
