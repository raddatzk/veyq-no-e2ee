<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { session, setUnlocked } from '../session'
import { logout } from '../../features/account/login/login'
import { enterSpace, loadCover, loadLevel, loadSpaces, type ActiveSpace, type LevelEntry, type SpaceEntry } from '../../features/home/home'
import type { SearchHit } from '../../features/search/search'
import MemoryDetailView from './MemoryDetailView.vue'
import SearchOverlay from './SearchOverlay.vue'

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
    loadCover(apiBase, session.unlocked!, active.value!, m.node.id)
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
  return s.kind === 'PERSONAL' ? 'Persönlich' : (s.role ?? 'Gruppe')
}

async function signOut() {
  try { await logout(apiBase, session.unlocked!.sessionToken) } catch { /* already gone */ }
  setUnlocked(null)
}

const personalSpaces = () => spaces.value.filter((s) => s.kind === 'PERSONAL')
const groupSpaces = () => spaces.value.filter((s) => s.kind === 'GROUP')
</script>

<template>
  <div class="home">
    <aside class="home-sidebar">
      <div class="home-brand">veyq</div>

      <div class="home-section">Bereich</div>
      <button v-for="s in personalSpaces()" :key="s.scopeId" class="space-row" :class="{ active: active?.entry.scopeId === s.scopeId }" @click="switchSpace(s)">
        <span class="space-avatar">{{ (s.displayName || '?').charAt(0).toUpperCase() }}</span>
        <span class="space-meta"><div class="space-name">{{ s.displayName }}</div><div class="space-sub">{{ spaceSub(s) }}</div></span>
      </button>

      <template v-if="groupSpaces().length">
        <div class="home-section">Gruppen</div>
        <button v-for="s in groupSpaces()" :key="s.scopeId" class="space-row" :class="{ active: active?.entry.scopeId === s.scopeId }" @click="switchSpace(s)">
          <span class="space-avatar">{{ (s.displayName || '?').charAt(0).toUpperCase() }}</span>
          <span class="space-meta"><div class="space-name">{{ s.displayName }}</div><div class="space-sub">{{ spaceSub(s) }}</div></span>
        </button>
      </template>

      <div class="home-spacer" />
      <button class="space-row" @click="signOut">
        <span class="space-avatar">{{ (session.unlocked?.user.displayName || '?').charAt(0).toUpperCase() }}</span>
        <span class="space-meta"><div class="space-name">{{ session.unlocked?.user.displayName }}</div><div class="space-sub">Abmelden</div></span>
      </button>
    </aside>

    <header class="home-topbar">
      <nav class="crumbs">
        <template v-for="(c, i) in crumbs" :key="i">
          <span v-if="i > 0" class="crumb-sep">/</span>
          <button class="crumb" :class="{ current: i === crumbs.length - 1 }" @click="gotoCrumb(i)">{{ c.name }}</button>
        </template>
      </nav>
      <input class="home-search" type="search" placeholder="Suche einfach überall…  ⌘K" readonly @focus="active && (searchOpen = true)" @click="active && (searchOpen = true)" />
    </header>

    <SearchOverlay v-if="searchOpen && active" :active="active" @close="searchOpen = false" @select="onSearchSelect" />

    <main class="home-content">
      <MemoryDetailView v-if="selectedMemory && active" :active="active" :memory="selectedMemory.node" :name="selectedMemory.name" @back="selectedMemory = null" />
      <template v-else>
        <div v-if="error" class="home-empty">{{ error }}</div>

        <div class="content-head"><span class="content-title">Memories</span><span class="content-count">{{ memories.length }}</span></div>
        <div v-if="memories.length" class="card-grid">
          <button v-for="m in memories" :key="m.node.id" class="memory-card" @click="selectedMemory = m">
            <img v-if="covers[m.node.id]" class="memory-cover" :src="covers[m.node.id]!" alt="" />
            <div v-else class="memory-cover placeholder">{{ covers[m.node.id] === null ? 'Kein Cover' : '…' }}</div>
            <div class="memory-label"><div class="memory-name">{{ m.name }}</div></div>
          </button>
        </div>
        <div v-else class="home-empty">Noch keine Memories hier.</div>

        <div class="content-head"><span class="content-title">Ordner</span><span class="content-count">{{ folders.length }}</span></div>
        <div v-if="folders.length" class="card-grid">
          <button v-for="f in folders" :key="f.node.id" class="folder-card" @click="openFolder(f)">
            <span class="folder-icon">📁</span><span class="folder-name">{{ f.name }}</span>
          </button>
        </div>
        <div v-else class="home-empty">Keine Ordner.</div>
      </template>
    </main>
  </div>
</template>
