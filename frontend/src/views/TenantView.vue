<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import Button from 'primevue/button'
import Message from 'primevue/message'
import ProgressSpinner from 'primevue/progressspinner'
import SelectButton from 'primevue/selectbutton'
import type {
  ExplorerAlbum,
  ExplorerFolder
} from '../features/explorer/api'
import AlbumCoverDialog from '../components/explorer/AlbumCoverDialog.vue'
import CreateLibraryItemDialog from '../components/explorer/CreateLibraryItemDialog.vue'
import DeleteExplorerItemDialog from '../components/explorer/DeleteExplorerItemDialog.vue'
import EditExplorerItemDialog from '../components/explorer/EditExplorerItemDialog.vue'
import ExplorerGrid from '../components/explorer/ExplorerGrid.vue'
import ExplorerMediaGrid from '../components/explorer/ExplorerMediaGrid.vue'
import MoveExplorerItemDialog from '../components/explorer/MoveExplorerItemDialog.vue'
import UploadMediaDialog from '../components/explorer/UploadMediaDialog.vue'
import { useExplorerData } from '../features/explorer/composables/useExplorerData'
import { useExplorerDialogs } from '../features/explorer/composables/useExplorerDialogs'
import { useTenantExplorerToolbar } from '../features/explorer/composables/useTenantExplorerToolbar'
import { useAuthState } from '../shared/state/useAuthState'
import { explorerPathToTenantRoute, routePathToExplorerPath } from '../shared/utils/paths'

const route = useRoute()
const router = useRouter()
const { authState } = useAuthState()

const tenantName = computed(() => String(route.params.tenantName || ''))
const tenant = computed(() => authState.tenants.find((item) => item.name === tenantName.value))
const tenantTitle = computed(() => tenant.value?.displayName || tenantName.value)
const explorerPath = computed(() => routePathToExplorerPath(route.params.pathMatch))
const viewOptions = [
  { label: 'Ordner und Alben', value: 'structure', icon: 'pi pi-folder' },
  { label: 'Alle Medien', value: 'media', icon: 'pi pi-images' }
]

const {
  activeView,
  canSwitchViews,
  contextType,
  error,
  explorer,
  isAlbumContext,
  isLibraryContext,
  loadExplorer,
  loading,
  media,
  mediaError,
  streaming,
  viewMode
} = useExplorerData(tenantName, explorerPath)

const dialogs = useExplorerDialogs()

useTenantExplorerToolbar({
  explorer,
  tenantName,
  tenantTitle,
  isAlbumContext,
  isLibraryContext,
  contextType,
  navigateToPath,
  openCreate: dialogs.openCreate,
  openContextSettings,
  openUpload: () => {
    dialogs.uploadOpen.value = true
  }
})

function openContextSettings() {
  const location = explorer.value?.location
  if (!location) return
  navigateToPath(location.path === '/' ? '/settings' : `${location.path}/settings`)
}

function navigateToPath(path: string) {
  void router.push(explorerPathToTenantRoute(tenantName.value, path))
}
</script>

<template>
  <div class="explorer-page">
    <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>
    <Message v-if="mediaError" severity="warn" :closable="false">{{ mediaError }}</Message>

    <div v-if="loading" class="explorer-loading">
      <ProgressSpinner class="small-spinner" />
      <span>Explorer wird geladen...</span>
    </div>

    <template v-else-if="explorer">
      <div v-if="canSwitchViews" class="explorer-view-switch">
        <SelectButton
          v-model="viewMode"
          :options="viewOptions"
          option-label="label"
          option-value="value"
          :allow-empty="false"
        >
          <template #option="{ option }">
            <span :class="option.icon" />
            <span>{{ option.label }}</span>
          </template>
        </SelectButton>
      </div>

      <ExplorerGrid
        v-if="activeView === 'structure'"
        :scope-roots="explorer.scopeRoots"
        :folders="explorer.folders"
        :albums="explorer.albums"
        @open-scope-root="navigateToPath($event.path)"
        @open-folder="navigateToPath($event.path)"
        @open-album="navigateToPath($event.path)"
        @edit-folder="dialogs.openEdit('folder', $event as ExplorerFolder)"
        @edit-album="dialogs.openEdit('album', $event as ExplorerAlbum)"
        @move-folder="dialogs.openMove('folder', $event as ExplorerFolder)"
        @move-album="dialogs.openMove('album', $event as ExplorerAlbum)"
        @cover-album="dialogs.openCover"
        @delete-folder="dialogs.openDelete('folder', $event as ExplorerFolder)"
        @delete-album="dialogs.openDelete('album', $event as ExplorerAlbum)"
      />

      <ExplorerMediaGrid v-else :media="media" :streaming="streaming" />
    </template>

    <div v-if="explorer" class="explorer-refresh">
      <Button
        label="Aktualisieren"
        icon="pi pi-refresh"
        severity="secondary"
        outlined
        @click="loadExplorer"
      />
    </div>

    <CreateLibraryItemDialog
      v-model:visible="dialogs.createOpen.value"
      :type="dialogs.createType.value"
      :tenant-name="tenantName"
      :parent-path="explorerPath"
      @saved="loadExplorer"
    />
    <UploadMediaDialog
      v-model:visible="dialogs.uploadOpen.value"
      :tenant-name="tenantName"
      :album-path="explorerPath"
      @saved="loadExplorer"
    />
    <EditExplorerItemDialog
      v-model:visible="dialogs.editOpen.value"
      :tenant-name="tenantName"
      :type="dialogs.selectedType.value"
      :item="dialogs.selectedItem.value"
      @saved="loadExplorer"
    />
    <MoveExplorerItemDialog
      v-model:visible="dialogs.moveOpen.value"
      :tenant-name="tenantName"
      :type="dialogs.selectedType.value"
      :item="dialogs.selectedItem.value"
      @saved="loadExplorer"
    />
    <DeleteExplorerItemDialog
      v-model:visible="dialogs.deleteOpen.value"
      :tenant-name="tenantName"
      :type="dialogs.selectedType.value"
      :item="dialogs.selectedItem.value"
      @deleted="loadExplorer"
    />
    <AlbumCoverDialog
      v-model:visible="dialogs.coverOpen.value"
      :tenant-name="tenantName"
      :album="dialogs.selectedAlbum.value"
      @saved="loadExplorer"
    />
  </div>
</template>
