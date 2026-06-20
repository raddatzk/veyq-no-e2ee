<script setup lang="ts">
// Global search topbar. Names are sealed (server-blind), so search is a CLIENT operation: load the active
// space's node graph, decode names, substring-match (searchNodes). A hit navigates by resolving its ancestor
// slug chain into a home path. The memory breadcrumb now lives in TenantView; old tenant search is gone.
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { searchNodes, type SearchHit } from '../../features/search/search'
import { ancestorChain } from '../../features/home/home'
import { buildHomePath } from '../../features/home/homeRoute'
import { session } from '../../app/session'
import { t } from '../../i18n/i18n'
import UploadStatusMenu from './UploadStatusMenu.vue'

const router = useRouter()
const searchInput = ref<HTMLInputElement | null>(null)
const query = ref('')
const results = ref<SearchHit[]>([])
const loading = ref(false)
const selectedIndex = ref(0)
let timer: number | null = null
let sequence = 0

const enabled = computed(() => session.activeSpace !== null)
const trimmed = computed(() => query.value.trim())

watch(query, () => {
  if (timer) window.clearTimeout(timer)
  if (trimmed.value.length < 2 || !enabled.value) {
    results.value = []
    loading.value = false
    return
  }
  loading.value = true
  timer = window.setTimeout(() => void run(), 220)
})

async function run() {
  const active = session.activeSpace
  if (!active || trimmed.value.length < 2) return
  const seq = ++sequence
  try {
    const hits = await searchNodes(session.apiBase, session.unlocked!, active, trimmed.value)
    if (seq !== sequence) return
    results.value = hits
    selectedIndex.value = hits.length ? 0 : -1
  } catch {
    if (seq === sequence) results.value = []
  } finally {
    if (seq === sequence) loading.value = false
  }
}

async function openResult(hit: SearchHit) {
  const active = session.activeSpace
  if (!active) return
  const chain = await ancestorChain(session.apiBase, session.unlocked!, active, hit.id)
  query.value = ''
  results.value = []
  void router.push(buildHomePath(active.entry, chain.map((c) => c.slug)))
}

function onKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') { query.value = ''; results.value = []; return }
  if (!results.value.length) return
  if (event.key === 'ArrowDown') { event.preventDefault(); selectedIndex.value = (selectedIndex.value + 1) % results.value.length }
  else if (event.key === 'ArrowUp') { event.preventDefault(); selectedIndex.value = (selectedIndex.value - 1 + results.value.length) % results.value.length }
  else if (event.key === 'Enter' && selectedIndex.value >= 0) { event.preventDefault(); void openResult(results.value[selectedIndex.value]) }
}

function onGlobalKeydown(event: KeyboardEvent) {
  if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') {
    event.preventDefault()
    void nextTick(() => searchInput.value?.focus())
  }
}

onMounted(() => document.addEventListener('keydown', onGlobalKeydown))
onBeforeUnmount(() => {
  document.removeEventListener('keydown', onGlobalKeydown)
  if (timer) window.clearTimeout(timer)
})
</script>

<template>
  <header class="workspace-topbar">
    <div class="workspace-search">
      <span class="pi pi-search" />
      <input
        ref="searchInput"
        v-model="query"
        class="workspace-search-input"
        type="search"
        :placeholder="t('search.placeholder')"
        :disabled="!enabled"
        @keydown="onKeydown"
      />
      <ul v-if="results.length" class="search-results">
        <li v-for="(hit, index) in results" :key="hit.id">
          <button type="button" class="search-result" :class="{ active: index === selectedIndex }" @click="openResult(hit)">
            <span :class="hit.type === 'FOLDER' ? 'pi pi-folder' : 'pi pi-images'" />
            <span class="search-result-name">{{ hit.name }}</span>
            <small>{{ hit.type === 'FOLDER' ? t('search.folder') : t('search.memory') }}</small>
          </button>
        </li>
      </ul>
      <p v-else-if="trimmed.length >= 2 && !loading" class="search-empty">{{ t('search.nothing') }}</p>
    </div>
    <UploadStatusMenu />
  </header>
</template>

<style scoped>
.workspace-topbar { display: flex; align-items: center; gap: 0.75rem; padding: 0.5rem 1rem; }
.workspace-search { position: relative; display: flex; align-items: center; gap: 0.5rem; width: min(100%, 520px); border: 1px solid var(--p-content-border-color, rgba(127,127,127,0.25)); border-radius: 10px; padding: 0.35rem 0.6rem; }
.workspace-search-input { flex: 1; border: none; background: none; color: inherit; outline: none; font: inherit; }
.search-results { position: absolute; top: calc(100% + 4px); left: 0; right: 0; z-index: 50; list-style: none; margin: 0; padding: 0.25rem; background: var(--p-content-background, #1e1e1e); border: 1px solid var(--p-content-border-color, rgba(127,127,127,0.25)); border-radius: 10px; max-height: 60vh; overflow: auto; }
.search-result { display: flex; align-items: center; gap: 0.6rem; width: 100%; background: none; border: none; padding: 0.5rem; border-radius: 8px; cursor: pointer; color: inherit; text-align: left; }
.search-result.active, .search-result:hover { background: var(--p-content-border-color, rgba(127,127,127,0.15)); }
.search-result-name { flex: 1; }
.search-result small { opacity: 0.6; }
.search-empty { position: absolute; top: calc(100% + 4px); left: 0; padding: 0.5rem 0.75rem; opacity: 0.6; }
</style>
