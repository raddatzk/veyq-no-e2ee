<script setup lang="ts">
import {computed, onBeforeUnmount, ref, watch} from 'vue'
import {useRoute, useRouter} from 'vue-router'
import Button from 'primevue/button'
import Message from 'primevue/message'
import ProgressSpinner from 'primevue/progressspinner'
import SelectButton from 'primevue/selectbutton'
import Tag from 'primevue/tag'
import {
  type ExplorerAlbum,
  type ExplorerContext,
  type ExplorerFolder,
  type ExplorerMedia,
  type ExplorerResponse,
  getExplorer,
  type LibraryItemType,
  streamExplorerMedia
} from '../api/explorerClient'
import {authState} from '../authState'
import AlbumCoverDialog from '../components/explorer/AlbumCoverDialog.vue'
import CreateLibraryItemDialog from '../components/explorer/CreateLibraryItemDialog.vue'
import DeleteExplorerItemDialog from '../components/explorer/DeleteExplorerItemDialog.vue'
import EditExplorerItemDialog from '../components/explorer/EditExplorerItemDialog.vue'
import ExplorerGrid from '../components/explorer/ExplorerGrid.vue'
import ExplorerMediaGrid from '../components/explorer/ExplorerMediaGrid.vue'
import MoveExplorerItemDialog from '../components/explorer/MoveExplorerItemDialog.vue'
import ShareExplorerItemDialog from '../components/explorer/ShareExplorerItemDialog.vue'
import UploadMediaDialog from '../components/explorer/UploadMediaDialog.vue'
import {clearExplorerToolbar, setExplorerToolbar} from '../composables/useExplorerToolbar'

const route = useRoute()
const router = useRouter()
const tenantName = computed(() => String(route.params.tenantName || ''))
const tenant = computed(() => authState.tenants.find((item) => item.name === tenantName.value))
const tenantTitle = computed(() => tenant.value?.displayName || tenantName.value)
const roleLabel = computed(() => tenant.value?.role === 'TenantOwner' ? 'Owner' : 'Member')
const explorerPath = computed(() => routePathToExplorerPath(route.params.pathMatch))

const explorer = ref<ExplorerResponse | null>(null)
const media = ref<ExplorerMedia[]>([])
const loading = ref(false)
const streaming = ref(false)
const error = ref('')
const mediaError = ref('')
const mediaLoaded = ref(false)
const streamController = ref<AbortController | null>(null)
let loadSequence = 0

type ExplorerViewMode = 'structure' | 'media'
const viewMode = ref<ExplorerViewMode>('structure')
const viewOptions = [
  {label: 'Ordner und Alben', value: 'structure', icon: 'pi pi-folder'},
  {label: 'Alle Medien', value: 'media', icon: 'pi pi-images'}
]

const createType = ref<LibraryItemType>('folder')
const createOpen = ref(false)
const editOpen = ref(false)
const moveOpen = ref(false)
const shareOpen = ref(false)
const deleteOpen = ref(false)
const coverOpen = ref(false)
const uploadOpen = ref(false)
const selectedType = ref<LibraryItemType>('folder')
const selectedItem = ref<ExplorerFolder | ExplorerAlbum | null>(null)
const selectedAlbum = computed(() => selectedType.value === 'album' ? selectedItem.value as ExplorerAlbum | null : null)
const contextType = computed(() => String(explorer.value?.context.type || '').toLowerCase())
const isAlbumContext = computed(() => contextType.value === 'album')
const isLibraryContext = computed(() => contextType.value === 'folder' || contextType.value === 'album')
const canSwitchViews = computed(() => !isAlbumContext.value)
const activeView = computed<ExplorerViewMode>(() => isAlbumContext.value ? 'media' : viewMode.value)

watch([tenantName, explorerPath], () => {
  viewMode.value = 'structure'
  void loadExplorer()
}, {immediate: true})

watch(explorer, updateToolbar, {immediate: true})
watch(activeView, updateMediaStreaming)

onBeforeUnmount(() => {
  abortStream()
  clearExplorerToolbar()
})

async function loadExplorer() {
  if (!tenantName.value) return
  const sequence = ++loadSequence
  abortStream()
  loading.value = true
  streaming.value = false
  error.value = ''
  mediaError.value = ''
  media.value = []
  mediaLoaded.value = false

  try {
    const response = await getExplorer(tenantName.value, explorerPath.value)
    if (sequence !== loadSequence) return
    explorer.value = response
    updateMediaStreaming()
  } catch (e: any) {
    if (sequence !== loadSequence) return
    explorer.value = null
    error.value = e.message || 'Explorer konnte nicht geladen werden'
    clearExplorerToolbar()
  } finally {
    if (sequence === loadSequence) loading.value = false
  }
}

function startMediaStream(sequence: number) {
  if (streaming.value || mediaLoaded.value) return
  const controller = new AbortController()
  streamController.value = controller
  streaming.value = true

  streamExplorerMedia(
      tenantName.value,
      explorerPath.value,
      (item) => {
        if (sequence === loadSequence) media.value = [...media.value, item]
      },
      {signal: controller.signal}
  ).catch((e: any) => {
    if (controller.signal.aborted || sequence !== loadSequence) return
    mediaError.value = e.message || 'Medien konnten nicht geladen werden'
  }).finally(() => {
    if (sequence === loadSequence) {
      streaming.value = false
      mediaLoaded.value = !controller.signal.aborted
    }
  })
}

function abortStream() {
  streamController.value?.abort()
  streamController.value = null
}

function updateMediaStreaming() {
  if (!explorer.value) return
  if (activeView.value === 'media') {
    startMediaStream(loadSequence)
  } else {
    abortStream()
    streaming.value = false
  }
}

function updateToolbar() {
  if (!explorer.value || !tenantName.value) {
    clearExplorerToolbar()
    return
  }

  const breadcrumbs = [
    {label: tenantTitle.value, command: () => navigateToPath('/')},
    ...explorer.value.breadcrumbs.map((breadcrumb) => ({
      label: breadcrumb.displayName,
      command: () => navigateToPath(breadcrumb.path)
    }))
  ]

  setExplorerToolbar(breadcrumbs, [
    {
      key: 'create-folder',
      label: 'Ordner',
      icon: 'pi pi-folder-plus',
      disabled: isAlbumContext.value,
      command: () => openCreate('folder')
    },
    {
      key: 'create-album',
      label: 'Album',
      icon: 'pi pi-images',
      disabled: isAlbumContext.value,
      command: () => openCreate('album')
    },
    {
      key: 'upload',
      label: 'Hochladen',
      icon: 'pi pi-upload',
      disabled: !isAlbumContext.value,
      command: () => {
        uploadOpen.value = true
      }
    },
    {
      key: 'settings',
      label: 'Einstellungen',
      icon: 'pi pi-cog',
      severity: 'secondary',
      outlined: true,
      disabled: !isLibraryContext.value,
      command: openContextSettings
    }
  ])
}

function openCreate(type: LibraryItemType) {
  createType.value = type
  createOpen.value = true
}

function openEdit(type: LibraryItemType, item: ExplorerFolder | ExplorerAlbum) {
  selectedType.value = type
  selectedItem.value = item
  editOpen.value = true
}

function openMove(type: LibraryItemType, item: ExplorerFolder | ExplorerAlbum) {
  selectedType.value = type
  selectedItem.value = item
  moveOpen.value = true
}

function openShare(type: LibraryItemType, item: ExplorerFolder | ExplorerAlbum) {
  selectedType.value = type
  selectedItem.value = item
  shareOpen.value = true
}

function openDelete(type: LibraryItemType, item: ExplorerFolder | ExplorerAlbum) {
  selectedType.value = type
  selectedItem.value = item
  deleteOpen.value = true
}

function openCover(album: ExplorerAlbum) {
  selectedType.value = 'album'
  selectedItem.value = album
  coverOpen.value = true
}

function openContextSettings() {
  const context = explorer.value?.context
  if (!context || contextType.value === 'tenant') return
  const type: LibraryItemType = contextType.value === 'album' ? 'album' : 'folder'
  openEdit(type, itemFromContext(context, type))
}

function itemFromContext(context: ExplorerContext, type: LibraryItemType): ExplorerFolder | ExplorerAlbum {
  const name = context.path.split('/').filter(Boolean).at(-1) || ''
  if (type === 'album') {
    return {id: context.path, name, displayName: context.displayName, path: context.path, coverMediaId: null}
  }
  return {id: context.path, name, displayName: context.displayName, path: context.path}
}

function navigateToPath(path: string) {
  const suffix = path && path !== '/' ? path : ''
  void router.push(`/${tenantName.value}${suffix}`)
}

function routePathToExplorerPath(value: string | string[] | undefined): string {
  const parts = Array.isArray(value) ? value : value ? [value] : []
  const normalized = parts.flatMap((part) => String(part).split('/')).filter(Boolean).join('/')
  return normalized ? `/${normalized}` : '/'
}
</script>

<template>
  <div class="explorer-page">
    <div class="explorer-titlebar">
      <div>
        <h1>{{ explorer?.context.displayName || tenantTitle }}</h1>
      </div>
      <Tag v-if="tenant" :value="roleLabel" severity="info"/>
    </div>

    <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>
    <Message v-if="mediaError" severity="warn" :closable="false">{{ mediaError }}</Message>

    <div v-if="loading" class="explorer-loading">
      <ProgressSpinner class="small-spinner"/>
      <span>Explorer wird geladen...</span>
    </div>

    <template v-else-if="explorer">
      <div v-if="canSwitchViews" class="explorer-view-switch">
        <SelectButton v-model="viewMode" :options="viewOptions" option-label="label" option-value="value"
                      :allow-empty="false">
          <template #option="{ option }">
            <span :class="option.icon"/>
            <span>{{ option.label }}</span>
          </template>
        </SelectButton>
      </div>

      <ExplorerGrid
          v-if="activeView === 'structure'"
          :folders="explorer.folders"
          :albums="explorer.albums"
          @open-folder="navigateToPath($event.path)"
          @open-album="navigateToPath($event.path)"
          @edit-folder="openEdit('folder', $event)"
          @edit-album="openEdit('album', $event)"
          @move-folder="openMove('folder', $event)"
          @move-album="openMove('album', $event)"
          @share-folder="openShare('folder', $event)"
          @share-album="openShare('album', $event)"
          @cover-album="openCover"
          @delete-folder="openDelete('folder', $event)"
          @delete-album="openDelete('album', $event)"
      />

      <ExplorerMediaGrid v-else :media="media" :streaming="streaming"/>
    </template>

    <div v-if="explorer" class="explorer-refresh">
      <Button label="Aktualisieren" icon="pi pi-refresh" severity="secondary" outlined @click="loadExplorer"/>
    </div>

    <CreateLibraryItemDialog
        v-model:visible="createOpen"
        :type="createType"
        :tenant-name="tenantName"
        :parent-path="explorerPath"
        @saved="loadExplorer"
    />
    <UploadMediaDialog
        v-model:visible="uploadOpen"
        :tenant-name="tenantName"
        :album-path="explorerPath"
        @saved="loadExplorer"
    />
    <EditExplorerItemDialog
        v-model:visible="editOpen"
        :tenant-name="tenantName"
        :type="selectedType"
        :item="selectedItem"
        @saved="loadExplorer"
    />
    <MoveExplorerItemDialog
        v-model:visible="moveOpen"
        :tenant-name="tenantName"
        :type="selectedType"
        :item="selectedItem"
        @saved="loadExplorer"
    />
    <ShareExplorerItemDialog
        v-model:visible="shareOpen"
        :tenant-name="tenantName"
        :type="selectedType"
        :item="selectedItem"
    />
    <DeleteExplorerItemDialog
        v-model:visible="deleteOpen"
        :tenant-name="tenantName"
        :type="selectedType"
        :item="selectedItem"
        @deleted="loadExplorer"
    />
    <AlbumCoverDialog
        v-model:visible="coverOpen"
        :tenant-name="tenantName"
        :album="selectedAlbum"
        @saved="loadExplorer"
    />
  </div>
</template>
