<script setup lang="ts">
// The workspace topbar, ported from the pre-promotion rich client (app/home/HomeTopbar): the active-space
// title, a ⌘K search button that opens the full search overlay, a list/grid view toggle, and a settings
// shortcut. Search stays a client-side, server-blind operation (SearchOverlay decodes names locally); a hit
// resolves its ancestor slug chain into a home path. Styling comes from the ported styles/home.css.
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import type { SearchHit } from '../../features/search/search'
import { ancestorChain } from '../../features/home/home'
import { buildHomePath } from '../../features/home/homeRoute'
import { session } from '../../app/session'
import { t } from '../../i18n/i18n'
import { toggleCompactView, useViewState } from '../../shared/state/useViewState'
import SearchOverlay from '../../app/home/SearchOverlay.vue'
import UploadStatusMenu from './UploadStatusMenu.vue'

const router = useRouter()
const { compactView } = useViewState()
const searchOpen = ref(false)

const activeSpace = computed(() => session.activeSpace)
const searchEnabled = computed(() => activeSpace.value !== null)
const spaceTitle = computed(() => activeSpace.value?.entry.displayName ?? '')
const shortcutLabel = computed(() => (isMac() ? '⌘K' : 'Ctrl K'))

function isMac(): boolean {
  return typeof navigator !== 'undefined' && /Mac|iPhone|iPad/.test(navigator.platform)
}

function openSettings() {
  void router.push('/~me/settings')
}

async function onSelect(hit: SearchHit) {
  const active = session.activeSpace
  searchOpen.value = false
  if (!active) return
  const chain = await ancestorChain(session.apiBase, session.unlocked!, active, hit.id)
  void router.push(buildHomePath(active.entry, chain.map((c) => c.slug)))
}

function onGlobalKeydown(event: KeyboardEvent) {
  if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') {
    event.preventDefault()
    if (searchEnabled.value) searchOpen.value = true
  }
}

onMounted(() => document.addEventListener('keydown', onGlobalKeydown))
onBeforeUnmount(() => document.removeEventListener('keydown', onGlobalKeydown))
</script>

<template>
  <header class="home-topbar">
    <nav class="crumbs">
      <span v-if="spaceTitle" class="crumb current">{{ spaceTitle }}</span>
    </nav>
    <button class="home-search" type="button" :disabled="!searchEnabled" @click="searchEnabled && (searchOpen = true)">
      <span class="home-search-icon" aria-hidden="true" />
      <span class="home-search-placeholder">{{ t('search.placeholder') }}</span>
      <kbd>{{ shortcutLabel }}</kbd>
    </button>
    <div class="home-topbar-actions">
      <UploadStatusMenu />
      <button
        class="home-topbar-icon"
        type="button"
        :class="{ active: compactView }"
        :title="t('home.viewOptions')"
        :aria-label="t('home.viewOptions')"
        :aria-pressed="compactView"
        @click="toggleCompactView"
      >
        <span class="home-topbar-list-icon" aria-hidden="true"></span>
      </button>
      <button class="home-topbar-icon" type="button" :title="t('home.settings')" :aria-label="t('home.settings')" @click="openSettings">
        <span class="home-topbar-gear-icon" aria-hidden="true"></span>
      </button>
    </div>
  </header>

  <SearchOverlay v-if="searchOpen && activeSpace" :active="activeSpace" @close="searchOpen = false" @select="onSelect" />
</template>
