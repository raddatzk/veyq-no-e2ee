<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { session } from '../session'
import { t } from '../../i18n/i18n'
import { enterSpace, loadCover, loadLevel, loadSpaces, type ActiveSpace, type LevelEntry, type SpaceEntry } from '../../features/home/home'
import type { SearchHit } from '../../features/search/search'
import type { NodeType } from '../../features/memory/createTree/memoryTree'
import MemoryDetailView from './MemoryDetailView.vue'
import SearchOverlay from './SearchOverlay.vue'
import SettingsOverlay from './SettingsOverlay.vue'
import CreateModal from './CreateModal.vue'
import RenameModal from './RenameModal.vue'

const apiBase = session.apiBase
const spaces = ref<SpaceEntry[]>([])
const active = ref<ActiveSpace | null>(null)
const crumbs = ref<{ id?: string; name: string }[]>([])
const folders = ref<LevelEntry[]>([])
const memories = ref<LevelEntry[]>([])
const covers = ref<Record<string, string | null>>({})
const error = ref('')
const selectedMemory = ref<LevelEntry | null>(null)
const searchOpen = ref(false)
const settingsOpen = ref(false)
const createType = ref<NodeType | null>(null)
const renameTarget = ref<LevelEntry | null>(null)

const currentParentId = () => (crumbs.value.length > 1 ? crumbs.value[crumbs.value.length - 1].id : undefined)
async function onCreated() {
  createType.value = null
  await openLevel()
}
async function onRenamed() {
  renameTarget.value = null
  await openLevel()
}

function onKeydown(e: KeyboardEvent) {
  if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
    e.preventDefault()
    if (active.value) searchOpen.value = true
  }
}

onMounted(async () => {
  window.addEventListener('keydown', onKeydown)
  try {
    spaces.value = await loadSpaces(apiBase, session.unlocked!)
    const personal = spaces.value.find((s) => s.kind === 'PERSONAL') ?? spaces.value[0]
    if (personal) await switchSpace(personal)
  } catch (e) {
    error.value = (e as Error).message
  }
})
onBeforeUnmount(() => window.removeEventListener('keydown', onKeydown))

async function onSearchSelect(hit: SearchHit) {
  searchOpen.value = false
  if (hit.type === 'FOLDER') {
    crumbs.value = [crumbs.value[0], { id: hit.id, name: hit.name }]
    await openLevel()
  } else {
    selectedMemory.value = { node: hit.node, name: hit.name }
  }
}

async function switchSpace(entry: SpaceEntry) {
  error.value = ''
  try {
    active.value = await enterSpace(apiBase, session.unlocked!, entry)
    crumbs.value = [{ name: entry.displayName }]
    await openLevel()
  } catch (e) {
    error.value = (e as Error).message
  }
}

async function openLevel() {
  selectedMemory.value = null
  const parentId = crumbs.value.length > 1 ? crumbs.value[crumbs.value.length - 1].id : undefined
  const entries = await loadLevel(apiBase, session.unlocked!, active.value!, parentId)
  folders.value = entries.filter((e) => e.node.type === 'FOLDER')
  memories.value = entries.filter((e) => e.node.type === 'MEMORY')
  covers.value = {}
  for (const m of memories.value) {
    loadCover(apiBase, session.unlocked!, active.value!, m.node)
      .then((url) => { covers.value[m.node.id] = url })
      .catch(() => {})
  }
}

async function openFolder(entry: LevelEntry) {
  crumbs.value.push({ id: entry.node.id, name: entry.name })
  await openLevel()
}

async function gotoCrumb(i: number) {
  if (i === crumbs.value.length - 1) return
  crumbs.value = crumbs.value.slice(0, i + 1)
  await openLevel()
}

function spaceSub(s: SpaceEntry): string {
  return s.kind === 'PERSONAL' ? t('home.personal') : (s.role ?? t('home.group'))
}

const personalSpaces = () => spaces.value.filter((s) => s.kind === 'PERSONAL')
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

      <template v-if="groupSpaces().length">
        <div class="home-section">{{ t('home.groups') }}</div>
        <button v-for="s in groupSpaces()" :key="s.scopeId" class="space-row" :class="{ active: active?.entry.scopeId === s.scopeId }" @click="switchSpace(s)">
          <span class="space-avatar">{{ (s.displayName || '?').charAt(0).toUpperCase() }}</span>
          <span class="space-meta"><div class="space-name">{{ s.displayName }}</div><div class="space-sub">{{ spaceSub(s) }}</div></span>
        </button>
      </template>

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
      <MemoryDetailView v-if="selectedMemory && active" :active="active" :memory="selectedMemory.node" :name="selectedMemory.name" @back="selectedMemory = null" />
      <template v-else>
        <div v-if="error" class="home-empty">{{ error }}</div>

        <div class="content-head"><span class="content-title">{{ t('home.memories') }}</span><span class="content-count">{{ memories.length }}</span></div>
        <div class="card-grid">
          <button v-for="m in memories" :key="m.node.id" class="memory-card" @click="selectedMemory = m">
            <img v-if="covers[m.node.id]" class="memory-cover" :src="covers[m.node.id]!" alt="" />
            <div v-else class="memory-cover placeholder">…</div>
            <div class="memory-label"><div class="memory-name">{{ m.name }}</div></div>
            <span class="card-more" :title="t('common.rename')" @click.stop="renameTarget = m">⋯</span>
          </button>
          <button class="create-card" @click="createType = 'MEMORY'"><span class="create-plus">＋</span>{{ t('home.createMemory') }}</button>
        </div>

        <div class="content-head"><span class="content-title">{{ t('home.folders') }}</span><span class="content-count">{{ folders.length }}</span></div>
        <div class="card-grid">
          <button v-for="f in folders" :key="f.node.id" class="folder-card" @click="openFolder(f)">
            <span class="folder-icon">📁</span><span class="folder-name">{{ f.name }}</span>
            <span class="card-more" :title="t('common.rename')" @click.stop="renameTarget = f">⋯</span>
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
  </div>
</template>
