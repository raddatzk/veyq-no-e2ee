<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { session } from '../session'
import { t } from '../../i18n/i18n'
import { ancestorChain, enterSpace, loadCover, loadLevel, loadSpaces, resolveRoute, type ActiveSpace, type LevelEntry, type SpaceEntry } from '../../features/home/home'
import { buildHomePath, matchSpace, parseHomePath } from '../../features/home/homeRoute'
import { deleteNode, reorderNode, slugify } from '../../features/memory/curate/curate'
import { cancelTenantDeletion } from '../../features/spaces/invite/groups'
import type { SearchHit } from '../../features/search/search'
import type { NodeType } from '../../features/memory/createTree/memoryTree'
import MemoryDetailView from './MemoryDetailView.vue'
import SearchOverlay from './SearchOverlay.vue'
import SettingsOverlay from './SettingsOverlay.vue'
import CreateModal from './CreateModal.vue'
import RenameModal from './RenameModal.vue'
import MovePicker from './MovePicker.vue'
import CreateGroupModal from './CreateGroupModal.vue'
import CreateGroupSpaceModal from './CreateGroupSpaceModal.vue'
import GroupMembersModal from './GroupMembersModal.vue'
import ConfirmDeleteModal from './ConfirmDeleteModal.vue'
import InviteModal from './InviteModal.vue'
import OpenInApp from '../auth/OpenInApp.vue'

const props = defineProps<{ openTarget?: { scopeId: string; nodeId: string } | null }>()
const apiBase = session.apiBase
const spaces = ref<SpaceEntry[]>([])
const active = ref<ActiveSpace | null>(null)
const crumbs = ref<{ id?: string; name: string; slug?: string }[]>([])
const folders = ref<LevelEntry[]>([])
const memories = ref<LevelEntry[]>([])
const covers = ref<Record<string, string | null>>({})
let loadSeq = 0 // bumped per navigation so superseded async cover/level loads can discard their results
const error = ref('')
const selectedMemory = ref<LevelEntry | null>(null)
const searchOpen = ref(false)
const settingsOpen = ref(false)
const createType = ref<NodeType | null>(null)
const renameTarget = ref<LevelEntry | null>(null)
const menuTarget = ref<LevelEntry | null>(null)
const moveTarget = ref<LevelEntry | null>(null)
const deleteNodeTarget = ref<LevelEntry | null>(null)
const dragged = ref<{ entry: LevelEntry; section: 'M' | 'F' } | null>(null)

async function confirmDeleteNode() {
  if (!deleteNodeTarget.value || !active.value || !session.unlocked) return
  try {
    await deleteNode(apiBase, session.unlocked, active.value, deleteNodeTarget.value.node.id)
    deleteNodeTarget.value = null
    await openLevel()
  } catch (e) {
    error.value = (e as Error).message
  }
}
const createGroupOpen = ref(false)
const createGroupSpaceOpen = ref(false)
const inviteTarget = ref<SpaceEntry | null>(null)
const manageGroup = ref<SpaceEntry | null>(null)

async function onGroupSpaceCreated() {
  createGroupSpaceOpen.value = false
  await reloadSpaces()
}

async function onGroupDeleted() {
  const wasActive = active.value?.entry.scopeId === manageGroup.value?.scopeId
  manageGroup.value = null
  if (wasActive) active.value = null
  await reloadSpaces()
}

async function onTenantDeleted() {
  // Deletion is only *scheduled* (grace period) — the tenant stays usable, so keep the user in it and
  // re-select it to surface the scheduled-deletion banner with its cancel action.
  const tid = inviteTarget.value?.tenantId
  inviteTarget.value = null
  await reloadSpaces(tid)
}

async function reloadSpaces(selectTenantId?: string) {
  spaces.value = await loadSpaces(apiBase, session.unlocked!)
  const target = selectTenantId ? spaces.value.find((s) => s.tenantId === selectTenantId) : undefined
  if (target) await switchSpace(target)
}
async function onGroupCreated(tenantId: string) {
  createGroupOpen.value = false
  await reloadSpaces(tenantId)
}

const currentParentId = () => (crumbs.value.length > 1 ? crumbs.value[crumbs.value.length - 1].id : undefined)
async function onCreated() {
  createType.value = null
  await openLevel()
}
async function onRenamed() {
  renameTarget.value = null
  await openLevel()
}
async function onMoved() {
  moveTarget.value = null
  await openLevel()
}

function onDragStart(entry: LevelEntry, section: 'M' | 'F') {
  dragged.value = { entry, section }
}
async function onReorderDrop(target: LevelEntry, section: 'M' | 'F') {
  const d = dragged.value
  dragged.value = null
  if (!d || d.section !== section || d.entry.node.id === target.node.id || !active.value) return
  const listRef = section === 'M' ? memories : folders
  const arr = [...listRef.value]
  const oldIndex = new Map(arr.map((e, i) => [e.node.id, i]))
  const from = arr.findIndex((e) => e.node.id === d.entry.node.id)
  const to = arr.findIndex((e) => e.node.id === target.node.id)
  if (from < 0 || to < 0) return
  arr.splice(to, 0, arr.splice(from, 1)[0])
  listRef.value = arr
  // Snapshot the space + a navigation token: persist only the indices that actually moved, and bail if the
  // user switched space/folder mid-write (so we never reorder against a now-different active space).
  const space = active.value
  const seq = loadSeq
  for (let i = 0; i < arr.length; i++) {
    if (oldIndex.get(arr[i].node.id) === i) continue
    if (seq !== loadSeq) return
    await reorderNode(apiBase, session.unlocked!, space, arr[i].node.id, i)
  }
}

function onKeydown(e: KeyboardEvent) {
  if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
    e.preventDefault()
    if (active.value) searchOpen.value = true
  }
}

onMounted(async () => {
  window.addEventListener('keydown', onKeydown)
  window.addEventListener('popstate', onPopState)
  try {
    spaces.value = await loadSpaces(apiBase, session.unlocked!)
    // Deep-link from a share link the member already holds: open that space + memory with its folder back-stack.
    const target = props.openTarget ? spaces.value.find((s) => s.scopeId === props.openTarget!.scopeId) : undefined
    if (target && props.openTarget) {
      active.value = await enterSpace(apiBase, session.unlocked!, target)
      const chain = await ancestorChain(apiBase, session.unlocked!, active.value, props.openTarget.nodeId)
      const memory = chain[chain.length - 1] ?? null
      crumbs.value = [{ name: target.displayName }, ...chain.slice(0, -1).map(toCrumb)]
      await openLevel()
      selectedMemory.value = memory
      syncUrl(false)
      return
    }
    // Otherwise restore the location from the path URL (a member-shared deep link), or land on personal.
    await restoreFromUrl()
  } catch (e) {
    error.value = (e as Error).message
  }
})
onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeydown)
  window.removeEventListener('popstate', onPopState)
  loadSeq++ // invalidate any in-flight cover loads so they don't touch a torn-down component
  revokeCovers()
})

const toCrumb = (f: LevelEntry) => ({ id: f.node.id, name: f.name, slug: f.slug })

/** Write the current location into the URL: <space prefix>/<folder slugs>/<memory slug>. */
function syncUrl(push: boolean) {
  if (!active.value) return
  const folderSlugs = crumbs.value.slice(1).map((c) => c.slug ?? '').filter(Boolean)
  const path = buildHomePath(active.value.entry, folderSlugs, selectedMemory.value?.slug)
  if (path === window.location.pathname) return
  if (push) history.pushState({}, '', path)
  else history.replaceState({}, '', path)
}

/** Re-derive the whole view from the URL path (initial load + browser back/forward). No history push. */
async function restoreFromUrl() {
  const route = parseHomePath(window.location.pathname)
  const target = (route ? matchSpace(spaces.value, route, window.location.host) : undefined)
    ?? spaces.value.find((s) => s.kind === 'PERSONAL') ?? spaces.value[0]
  if (!target) return
  if (active.value?.entry.scopeId !== target.scopeId) active.value = await enterSpace(apiBase, session.unlocked!, target)
  const { folders: fldrs, memory } = await resolveRoute(apiBase, session.unlocked!, active.value!, route?.segments ?? [])
  crumbs.value = [{ name: target.displayName }, ...fldrs.map(toCrumb)]
  await openLevel()
  selectedMemory.value = memory
  syncUrl(false)
}

function onPopState() {
  restoreFromUrl().catch((e) => { error.value = (e as Error).message })
}

async function onSearchSelect(hit: SearchHit) {
  searchOpen.value = false
  if (hit.type === 'FOLDER') {
    crumbs.value = [crumbs.value[0], { id: hit.id, name: hit.name, slug: slugify(hit.name) }]
    await openLevel()
    syncUrl(true)
  } else {
    selectedMemory.value = { node: hit.node, name: hit.name, slug: slugify(hit.name) }
    syncUrl(true)
  }
}

async function switchSpace(entry: SpaceEntry, push = true) {
  // A tenant/group lives on its tenant's domain (the host resolves the tenant). Switching to one on a
  // DIFFERENT host is a cross-origin navigation to that domain. Skipped in local dev (fake .test domains).
  const isDev = location.hostname === 'localhost' || location.hostname === '127.0.0.1'
  if (!isDev && entry.domain && entry.domain !== location.host) {
    location.assign(`${location.protocol}//${entry.domain}${buildHomePath(entry, [])}`)
    return
  }
  error.value = ''
  try {
    active.value = await enterSpace(apiBase, session.unlocked!, entry)
    crumbs.value = [{ name: entry.displayName }]
    await openLevel()
    syncUrl(push)
  } catch (e) {
    error.value = (e as Error).message
  }
}

/** Revoke every currently-held cover object URL (covers come from URL.createObjectURL → must be freed). */
function revokeCovers() {
  for (const u of Object.values(covers.value)) if (u) URL.revokeObjectURL(u)
}

async function openLevel() {
  const seq = ++loadSeq // a generation token: late async results from a superseded navigation are discarded
  selectedMemory.value = null
  const parentId = crumbs.value.length > 1 ? crumbs.value[crumbs.value.length - 1].id : undefined
  const entries = await loadLevel(apiBase, session.unlocked!, active.value!, parentId)
  if (seq !== loadSeq) return // a newer openLevel started while we awaited — drop this stale level
  folders.value = entries.filter((e) => e.node.type === 'FOLDER')
  memories.value = entries.filter((e) => e.node.type === 'MEMORY')
  revokeCovers()
  covers.value = {}
  for (const m of memories.value) {
    loadCover(apiBase, session.unlocked!, active.value!, m.node)
      .then((url) => {
        if (seq !== loadSeq) { if (url) URL.revokeObjectURL(url); return } // landed after a newer navigation
        if (url) covers.value[m.node.id] = url
      })
      .catch(() => {})
  }
}

async function openFolder(entry: LevelEntry) {
  crumbs.value.push(toCrumb(entry))
  await openLevel()
  syncUrl(true)
}

async function gotoCrumb(i: number) {
  if (i === crumbs.value.length - 1) return
  crumbs.value = crumbs.value.slice(0, i + 1)
  await openLevel()
  syncUrl(true)
}

/** Open a memory (its slug joins the URL) / go back to the folder (drop it from the URL). */
function openMemory(m: LevelEntry) {
  selectedMemory.value = m
  syncUrl(true)
}
function closeMemory() {
  selectedMemory.value = null
  syncUrl(false)
}

/** The current location as a member-shareable path (for the "open in app" deep link). */
const currentPath = computed(() => {
  if (!active.value) return '/'
  const folderSlugs = crumbs.value.slice(1).map((c) => c.slug ?? '').filter(Boolean)
  return buildHomePath(active.value.entry, folderSlugs, selectedMemory.value?.slug)
})

function spaceSub(s: SpaceEntry): string {
  return s.kind === 'PERSONAL' ? t('home.personal') : (s.role ?? t('home.group'))
}

async function cancelDeletion() {
  const entry = active.value?.entry
  if (!entry?.tenantId || !session.unlocked) return
  try {
    await cancelTenantDeletion(apiBase, session.unlocked, entry.tenantId)
    await reloadSpaces(entry.tenantId)
  } catch (e) {
    error.value = (e as Error).message
  }
}

const personalSpaces = () => spaces.value.filter((s) => s.kind === 'PERSONAL')
const tenantSpaces = () => spaces.value.filter((s) => s.kind === 'TENANT')
const groupSpaces = () => spaces.value.filter((s) => s.kind === 'GROUP')
</script>

<template>
  <div class="home">
    <aside class="home-sidebar">
      <div class="home-brand">veyq</div>

      <div class="home-section">{{ t('home.spaces') }}</div>
      <button v-for="s in personalSpaces()" :key="s.scopeId" class="space-row" :class="{ active: active?.entry.scopeId === s.scopeId }" @click="switchSpace(s)">
        <span class="space-avatar">{{ (s.displayName || '?').charAt(0).toUpperCase() }}</span>
        <span class="space-meta"><div class="space-name">{{ s.displayName }}</div><div class="space-sub">{{ spaceSub(s) }}</div></span>
      </button>

      <div class="home-section">{{ t('home.tenants') }}</div>
      <div v-for="s in tenantSpaces()" :key="s.scopeId" class="space-row-wrap">
        <button class="space-row" :class="{ active: active?.entry.scopeId === s.scopeId }" @click="switchSpace(s)">
          <span class="space-avatar">{{ (s.displayName || '?').charAt(0).toUpperCase() }}</span>
          <span class="space-meta"><div class="space-name">{{ s.displayName }}</div><div class="space-sub">{{ spaceSub(s) }}</div></span>
        </button>
        <button v-if="s.role === 'Owner'" class="space-invite" :title="t('groups.invite')" @click.stop="inviteTarget = s">＋</button>
      </div>
      <button class="space-row space-create" @click="createGroupOpen = true">
        <span class="space-avatar space-avatar-ghost">＋</span>
        <span class="space-meta"><div class="space-name">{{ t('tenants.create') }}</div></span>
      </button>

      <div class="home-section">{{ t('home.groups') }}</div>
      <div v-for="s in groupSpaces()" :key="s.scopeId" class="space-row-wrap">
        <button class="space-row" :class="{ active: active?.entry.scopeId === s.scopeId }" @click="switchSpace(s)">
          <span class="space-avatar">{{ (s.displayName || '?').charAt(0).toUpperCase() }}</span>
          <span class="space-meta"><div class="space-name">{{ s.displayName }}</div><div class="space-sub">{{ spaceSub(s) }}</div></span>
        </button>
        <button v-if="s.role === 'GroupOwner'" class="space-invite" :title="t('groups.members')" @click.stop="manageGroup = s">⚙</button>
      </div>
      <button v-if="tenantSpaces().length" class="space-row space-create" @click="createGroupSpaceOpen = true">
        <span class="space-avatar space-avatar-ghost">＋</span>
        <span class="space-meta"><div class="space-name">{{ t('groups.create') }}</div></span>
      </button>

      <div class="home-spacer" />
      <button class="space-row" @click="settingsOpen = true">
        <span class="space-avatar">{{ (session.unlocked?.user.displayName || '?').charAt(0).toUpperCase() }}</span>
        <span class="space-meta"><div class="space-name">{{ session.unlocked?.user.displayName }}</div><div class="space-sub">{{ t('home.settings') }}</div></span>
      </button>
    </aside>

    <SettingsOverlay v-if="settingsOpen" @close="settingsOpen = false" />

    <header class="home-topbar">
      <nav class="crumbs">
        <template v-for="(c, i) in crumbs" :key="i">
          <span v-if="i > 0" class="crumb-sep">/</span>
          <button class="crumb" :class="{ current: i === crumbs.length - 1 }" @click="gotoCrumb(i)">{{ c.name }}</button>
        </template>
      </nav>
      <input class="home-search" type="search" :placeholder="t('home.search')" readonly @focus="active && (searchOpen = true)" @click="active && (searchOpen = true)" />
    </header>

    <SearchOverlay v-if="searchOpen && active" :active="active" @close="searchOpen = false" @select="onSearchSelect" />

    <main class="home-content">
      <OpenInApp persist-key="home" :path="currentPath" />
      <div v-if="active?.entry.scheduledDeletionAt" class="deletion-banner">
        <span class="deletion-banner-text">{{ t('delete.scheduledBanner', { date: new Date(active.entry.scheduledDeletionAt).toLocaleDateString() }) }}</span>
        <button v-if="active.entry.role === 'Owner'" class="btn btn-ghost deletion-banner-cancel" type="button" @click="cancelDeletion">{{ t('delete.cancelDeletion') }}</button>
      </div>
      <!-- :key forces a remount when the memory changes (e.g. search-selecting a different one without going
           through openLevel), so the gallery/journal/tracks reload instead of showing the previous memory's. -->
      <MemoryDetailView v-if="selectedMemory && active" :key="selectedMemory.node.id" :active="active" :memory="selectedMemory.node" :name="selectedMemory.name" @back="closeMemory" />
      <template v-else>
        <div v-if="error" class="home-empty">{{ error }}</div>

        <div class="content-head"><span class="content-title">{{ t('home.memories') }}</span><span class="content-count">{{ memories.length }}</span></div>
        <div class="card-grid">
          <button
            v-for="m in memories"
            :key="m.node.id"
            class="memory-card"
            draggable="true"
            @click="openMemory(m)"
            @dragstart="onDragStart(m, 'M')"
            @dragover.prevent
            @drop="onReorderDrop(m, 'M')"
          >
            <img v-if="covers[m.node.id]" class="memory-cover" :src="covers[m.node.id]!" alt="" />
            <div v-else class="memory-cover placeholder">…</div>
            <div class="memory-label"><div class="memory-name">{{ m.name }}</div></div>
            <span class="card-more" @click.stop="menuTarget = m">⋯</span>
          </button>
          <button class="create-card" @click="createType = 'MEMORY'"><span class="create-plus">＋</span>{{ t('home.createMemory') }}</button>
        </div>

        <div class="content-head"><span class="content-title">{{ t('home.folders') }}</span><span class="content-count">{{ folders.length }}</span></div>
        <div class="card-grid">
          <button
            v-for="f in folders"
            :key="f.node.id"
            class="folder-card"
            draggable="true"
            @click="openFolder(f)"
            @dragstart="onDragStart(f, 'F')"
            @dragover.prevent
            @drop="onReorderDrop(f, 'F')"
          >
            <span class="folder-icon">📁</span><span class="folder-name">{{ f.name }}</span>
            <span class="card-more" @click.stop="menuTarget = f">⋯</span>
          </button>
          <button class="create-card create-card-row" @click="createType = 'FOLDER'"><span class="create-plus">＋</span>{{ t('home.createFolder') }}</button>
        </div>
      </template>
    </main>

    <CreateModal
      v-if="createType && active"
      :active="active"
      :type="createType"
      :parent-id="currentParentId()"
      :order-index="folders.length + memories.length"
      @close="createType = null"
      @created="onCreated"
    />
    <RenameModal v-if="renameTarget && active" :active="active" :entry="renameTarget" @close="renameTarget = null" @renamed="onRenamed" />
    <MovePicker v-if="moveTarget && active" :active="active" :entry="moveTarget" @close="moveTarget = null" @moved="onMoved" />
    <CreateGroupModal v-if="createGroupOpen" @close="createGroupOpen = false" @created="onGroupCreated" />
    <CreateGroupSpaceModal v-if="createGroupSpaceOpen" :tenants="tenantSpaces()" @close="createGroupSpaceOpen = false" @created="onGroupSpaceCreated" />
    <GroupMembersModal v-if="manageGroup" :space="manageGroup" @close="manageGroup = null" @deleted="onGroupDeleted" />
    <InviteModal v-if="inviteTarget" :space="inviteTarget" @close="inviteTarget = null" @deleted="onTenantDeleted" />

    <div v-if="menuTarget" class="settings-backdrop" @click.self="menuTarget = null">
      <div class="settings-panel action-sheet">
        <div class="settings-head"><h2 class="settings-title">{{ menuTarget.name }}</h2></div>
        <button class="btn btn-ghost action-row" type="button" @click="renameTarget = menuTarget; menuTarget = null">{{ t('common.rename') }}</button>
        <button class="btn btn-ghost action-row" type="button" @click="moveTarget = menuTarget; menuTarget = null">{{ t('common.move') }}</button>
        <button class="btn btn-ghost action-row" type="button" style="color: var(--app-danger)" @click="deleteNodeTarget = menuTarget; menuTarget = null">{{ t('common.delete') }}</button>
      </div>
    </div>

    <ConfirmDeleteModal v-if="deleteNodeTarget" :name="deleteNodeTarget.name" @close="deleteNodeTarget = null" @confirm="confirmDeleteNode" />
  </div>
</template>
