<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import Button from 'primevue/button'
import Message from 'primevue/message'
import ProgressSpinner from 'primevue/progressspinner'
import CreateFolderDialog from '../features/memories/components/CreateFolderDialog.vue'
import CreateMemoryDialog from '../features/memories/components/CreateMemoryDialog.vue'
import MemoryDetail from '../features/memories/components/MemoryDetail.vue'
import MemoryFolderOverview from '../features/memories/components/MemoryFolderOverview.vue'
import MemoryUploadDialog from '../features/memories/components/MemoryUploadDialog.vue'
import { useMemoryRouteData } from '../features/memories/composables/useMemoryRouteData'
import { useMemoryToolbar } from '../features/memories/composables/useMemoryToolbar'
import { useAuthState } from '../shared/state/useAuthState'
import { memoryPathToTenantRoute, routePathToMemoryPath } from '../shared/utils/paths'

const route = useRoute()
const router = useRouter()
const { authState } = useAuthState()

const tenantName = computed(() => String(route.params.tenantName || ''))
const tenant = computed(() => authState.tenants.find((item) => item.name === tenantName.value))
const tenantTitle = computed(() => tenant.value?.displayName || tenantName.value)
const routePath = computed(() => routePathToMemoryPath(route.params.pathMatch))
const createFolderOpen = ref(false)
const createMemoryOpen = ref(false)
const uploadOpen = ref(false)

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
  navigation
} = useMemoryRouteData(tenantName, routePath)

useMemoryToolbar({
  navigation,
  memory,
  loading,
  tenantName,
  tenantLabel: tenantTitle,
  routePath,
  openUpload: () => {
    uploadOpen.value = true
  },
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
      <span>Memories werden geladen...</span>
    </div>

    <MemoryDetail
      v-else-if="isMemoryRoute && memory"
      :tenant-name="tenantName"
      :memory="memory"
      :child-memories="childMemories"
      :media="media"
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

    <div v-if="navigation || memory" class="memory-refresh">
      <Button
        label="Aktualisieren"
        icon="pi pi-refresh"
        severity="secondary"
        outlined
        @click="load"
      />
    </div>

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
    <MemoryUploadDialog
      v-model:visible="uploadOpen"
      :tenant-name="tenantName"
      :memory-id="memory?.id || null"
      @saved="load"
    />
  </div>
</template>
