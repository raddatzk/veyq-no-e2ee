<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import Message from 'primevue/message'
import ProgressSpinner from 'primevue/progressspinner'
import CreateFolderDialog from '../features/memories/components/CreateFolderDialog.vue'
import CreateMemoryDialog from '../features/memories/components/CreateMemoryDialog.vue'
import MemoryDetail from '../features/memories/components/MemoryDetail.vue'
import MemoryFolderOverview from '../features/memories/components/MemoryFolderOverview.vue'
import { updateMemoryJournal } from '../features/memories/api'
import { useMemoryRouteData } from '../features/memories/composables/useMemoryRouteData'
import { useMemoryToolbar } from '../features/memories/composables/useMemoryToolbar'
import { useAuthState } from '../shared/state/useAuthState'
import { memoryPathToTenantRoute, routePathToMemoryPath } from '../shared/utils/paths'
import { memoryTabFromHash, memoryTabToHash, type MemoryDetailTab } from '../features/memories/utils/memoryTabs'

const route = useRoute()
const router = useRouter()
const { t } = useI18n()
const { authState } = useAuthState()

const tenantName = computed(() => String(route.params.tenantName || ''))
const tenant = computed(() => authState.tenants.find((item) => item.name === tenantName.value))
const tenantTitle = computed(() => tenant.value?.displayName || tenantName.value)
const routePath = computed(() => routePathToMemoryPath(route.params.pathMatch))
const activeMemoryTab = computed(() => memoryTabFromHash(route.hash))
const createFolderOpen = ref(false)
const createMemoryOpen = ref(false)

const {
  childMemories,
  currentFolderPath,
  error,
  isMemoryRoute,
  load,
  loading,
  media,
  memories,
  memory,
  memoryBreadcrumbs,
  memoryJournal,
  memoryHeader,
  playlists,
  tracks,
  navigation
} = useMemoryRouteData(tenantName, routePath)

useMemoryToolbar({
  navigation,
  memory,
  memoryBreadcrumbs,
  loading,
  tenantName,
  tenantLabel: tenantTitle,
  routePath,
  openContextSettings,
  navigateToPath
})

function openContextSettings() {
  const location = navigation.value?.location
  if (!location) return
  navigateToPath(location.path === '/' ? '/settings' : `${location.path}/settings`)
}

function navigateToPath(path: string) {
  void router.push(memoryPathToTenantRoute(tenantName.value, path))
}

function updateActiveMemoryTab(tab: MemoryDetailTab) {
  void router.replace({ path: route.path, query: route.query, hash: memoryTabToHash(tab) })
}

function handleMemoryChanged(updatedMemory: { path: string }) {
  if (updatedMemory.path !== routePath.value) {
    navigateToPath(updatedMemory.path)
    return
  }
  void load()
}
</script>

<template>
  <div class="memory-page memory-page">
    <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>

    <div v-if="loading" class="memory-loading">
      <ProgressSpinner class="small-spinner" />
      <span>{{ t('memory.loading') }}</span>
    </div>

    <MemoryDetail
      v-else-if="isMemoryRoute && memory"
      :tenant-name="tenantName"
      :memory="memory"
      :header="memoryHeader"
      :journal="memoryJournal"
      :child-memories="childMemories"
      :media="media"
      :playlists="playlists"
      :tracks="tracks"
      :active-tab="activeMemoryTab"
      :save-journal="(memoryId, request) => updateMemoryJournal(tenantName, memoryId, request)"
      @update:active-tab="updateActiveMemoryTab"
      @open-memory="navigateToPath($event.path)"
      @create-child-memory="createMemoryOpen = true"
      @changed="handleMemoryChanged"
      @deleted="navigateToPath"
    />

    <MemoryFolderOverview
      v-else-if="navigation"
      :navigation="navigation"
      :memories="memories"
      @open-folder="navigateToPath($event.path)"
      @open-memory="navigateToPath($event.path)"
      @create-folder="createFolderOpen = true"
      @create-memory="createMemoryOpen = true"
    />

    <CreateFolderDialog
      v-model:visible="createFolderOpen"
      :tenant-name="tenantName"
      :tenant-display-name="tenantTitle"
      :parent-path="currentFolderPath"
      @saved="load"
    />
    <CreateMemoryDialog
      v-model:visible="createMemoryOpen"
      :tenant-name="tenantName"
      :tenant-display-name="tenantTitle"
      :folder-path="currentFolderPath"
      :parent-memory-id="memory?.id || null"
      @saved="load"
    />
  </div>
</template>
