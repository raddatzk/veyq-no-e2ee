<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import Button from 'primevue/button'
import Breadcrumb from 'primevue/breadcrumb'
import { API_BASE } from '../../shared/api/httpClient'
import { memoryPathToTenantRoute } from '../../shared/utils/paths'
import { useBreadcrumbs } from '../../composables/useBreadcrumbs'
import { workspaceToolbar } from '../../composables/useWorkspaceToolbar'
import { searchTenant, type SearchResultSummary } from '../../features/memories/api'
import UploadStatusMenu from '../../features/memories/components/UploadStatusMenu.vue'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const { fallbackBreadcrumb } = useBreadcrumbs()
const fallbackBreadcrumbs = computed(() => [fallbackBreadcrumb.value])
const items = computed(() =>
  workspaceToolbar.active ? workspaceToolbar.breadcrumbs : fallbackBreadcrumbs.value
)
const actions = computed(() => (workspaceToolbar.active ? workspaceToolbar.actions : []))
const searchRoot = ref<HTMLElement | null>(null)
const searchInput = ref<HTMLInputElement | null>(null)
const searchOpen = ref(false)
const searchQuery = ref('')
const searchResults = ref<SearchResultSummary[]>([])
const searchLoading = ref(false)
const searchError = ref('')
const selectedIndex = ref(0)
let searchTimer: number | null = null
let searchSequence = 0

const currentTenantName = computed(() => {
  if (route.name !== 'tenant') return ''
  const tenantName = route.params.tenantName
  return Array.isArray(tenantName) ? tenantName[0] : String(tenantName || '')
})
const searchEnabled = computed(() => currentTenantName.value.length > 0)
const shortcutLabel = computed(() => (isMacPlatform() ? '⌘K' : 'Ctrl K'))
const trimmedQuery = computed(() => searchQuery.value.trim())
const showSearchEmpty = computed(
  () => trimmedQuery.value.length >= 2 && !searchLoading.value && !searchError.value && searchResults.value.length === 0
)

watch(searchQuery, () => {
  if (searchTimer) window.clearTimeout(searchTimer)
  searchError.value = ''
  if (trimmedQuery.value.length < 2 || !searchEnabled.value) {
    searchResults.value = []
    searchLoading.value = false
    selectedIndex.value = 0
    return
  }
  searchLoading.value = true
  searchTimer = window.setTimeout(() => {
    void runSearch()
  }, 220)
})

watch(searchResults, () => {
  selectedIndex.value = searchResults.value.length > 0 ? 0 : -1
})

onMounted(() => {
  document.addEventListener('keydown', handleGlobalKeydown)
  document.addEventListener('pointerdown', handlePointerDown)
})

onBeforeUnmount(() => {
  document.removeEventListener('keydown', handleGlobalKeydown)
  document.removeEventListener('pointerdown', handlePointerDown)
  if (searchTimer) window.clearTimeout(searchTimer)
})

function openSearch() {
  if (!searchEnabled.value) return
  searchOpen.value = true
  void nextTick(() => searchInput.value?.focus())
}

function closeSearch() {
  searchOpen.value = false
  searchError.value = ''
}

async function runSearch() {
  if (!searchEnabled.value || trimmedQuery.value.length < 2) return
  const sequence = ++searchSequence
  searchLoading.value = true
  searchError.value = ''
  try {
    const results = await searchTenant(currentTenantName.value, trimmedQuery.value, 20)
    if (sequence !== searchSequence) return
    searchResults.value = results
  } catch {
    if (sequence !== searchSequence) return
    searchResults.value = []
    searchError.value = t('navigation.searchFailed')
  } finally {
    if (sequence === searchSequence) searchLoading.value = false
  }
}

function handleGlobalKeydown(event: KeyboardEvent) {
  const isShortcut = (event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k'
  if (!isShortcut) return
  event.preventDefault()
  openSearch()
}

function handlePointerDown(event: PointerEvent) {
  if (!searchOpen.value) return
  const root = searchRoot.value
  if (root && event.target instanceof Node && !root.contains(event.target)) closeSearch()
}

function handleSearchKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') {
    event.preventDefault()
    closeSearch()
    return
  }
  if (!searchResults.value.length) return
  if (event.key === 'ArrowDown') {
    event.preventDefault()
    selectedIndex.value = (selectedIndex.value + 1) % searchResults.value.length
  } else if (event.key === 'ArrowUp') {
    event.preventDefault()
    selectedIndex.value =
      (selectedIndex.value - 1 + searchResults.value.length) % searchResults.value.length
  } else if (event.key === 'Enter' && selectedIndex.value >= 0) {
    event.preventDefault()
    openResult(searchResults.value[selectedIndex.value])
  }
}

function openResult(result: SearchResultSummary) {
  if (!searchEnabled.value) return
  closeSearch()
  searchQuery.value = ''
  searchResults.value = []
  void router.push(memoryPathToTenantRoute(currentTenantName.value, result.path))
}

function resultIcon(result: SearchResultSummary): string {
  return result.kind === 'MEMORY' ? 'pi pi-images' : 'pi pi-folder'
}

function resultKindLabel(result: SearchResultSummary): string {
  return result.kind === 'MEMORY' ? t('memory.title') : t('memory.folders')
}

function coverUrl(result: SearchResultSummary): string {
  return result.coverUrl ? `${API_BASE}${result.coverUrl}` : ''
}

function isMacPlatform(): boolean {
  return typeof navigator !== 'undefined' && /Mac|iPhone|iPad/.test(navigator.platform)
}
</script>

<template>
  <header class="workspace-topbar">
    <Breadcrumb :model="items" class="app-breadcrumb" />
    <div ref="searchRoot" class="workspace-search">
      <label
        class="workspace-topbar-center"
        :aria-label="t('navigation.globalSearch')"
        :class="{ 'workspace-topbar-center-disabled': !searchEnabled }"
        @click="openSearch"
      >
        <span class="pi pi-search" />
        <input
          ref="searchInput"
          v-model="searchQuery"
          type="search"
          :disabled="!searchEnabled"
          :placeholder="t('navigation.searchPlaceholder')"
          autocomplete="off"
          @focus="openSearch"
          @keydown="handleSearchKeydown"
        />
        <kbd>{{ shortcutLabel }}</kbd>
      </label>
      <div v-if="searchOpen" class="workspace-search-panel" role="listbox" :aria-label="t('navigation.globalSearch')">
        <div class="workspace-search-results" role="listbox">
          <p v-if="trimmedQuery.length < 2" class="workspace-search-state">
            {{ t('navigation.searchMinLength') }}
          </p>
          <p v-else-if="searchLoading" class="workspace-search-state">
            {{ t('navigation.searchLoading') }}
          </p>
          <p v-else-if="searchError" class="workspace-search-state workspace-search-state-error">
            {{ searchError }}
          </p>
          <p v-else-if="showSearchEmpty" class="workspace-search-state">
            {{ t('navigation.searchEmpty') }}
          </p>
          <template v-else>
            <button
              v-for="(result, index) in searchResults"
              :key="`${result.kind}-${result.id}`"
              class="workspace-search-result"
              :class="{ 'workspace-search-result-active': index === selectedIndex }"
              type="button"
              role="option"
              :aria-selected="index === selectedIndex"
              @mouseenter="selectedIndex = index"
              @click="openResult(result)"
            >
              <img v-if="coverUrl(result)" :src="coverUrl(result)" alt="" />
              <span v-else class="workspace-search-result-icon" :class="resultIcon(result)" />
              <span class="workspace-search-result-text">
                <strong>{{ result.displayName }}</strong>
                <small>{{ result.path }}</small>
                <small v-if="result.journalSnippet">{{ result.journalSnippet }}</small>
              </span>
              <span class="workspace-search-result-kind">{{ resultKindLabel(result) }}</span>
            </button>
          </template>
        </div>
      </div>
    </div>
    <div class="workspace-topbar-actions">
      <UploadStatusMenu />
      <div v-if="actions.length" class="workspace-actions">
        <Button
          v-for="action in actions"
          :key="action.key"
          :icon="action.icon"
          :aria-label="action.label"
          :severity="action.severity"
          :outlined="action.outlined"
          size="small"
          @click="action.command"
        />
      </div>
    </div>
  </header>
</template>
