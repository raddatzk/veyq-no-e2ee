<script setup lang="ts">
import { computed, ref } from 'vue'
import Button from 'primevue/button'
import Tab from 'primevue/tab'
import TabList from 'primevue/tablist'
import TabPanel from 'primevue/tabpanel'
import TabPanels from 'primevue/tabpanels'
import Tabs from 'primevue/tabs'
import MediaGrid from '../../../components/media/MediaGrid.vue'
import { API_BASE } from '../../../shared/api/httpClient'
import type {
  Memory,
  MemoryMedia,
  SetMemoryCoverRequest,
  UpdateMemoryMediaMetadataRequest,
  UpdateMemoryRequest,
  UploadMemoryMediaRequest
} from '../api'
import DeleteMemoryDialog from './DeleteMemoryDialog.vue'
import EditMemoryDialog from './EditMemoryDialog.vue'
import MemoryCard from './MemoryCard.vue'
import MemoryCoverImage from './MemoryCoverImage.vue'
import MemoryCoverDialog from './MemoryCoverDialog.vue'
import MemoryShareDialog from './MemoryShareDialog.vue'
import MemoryUploadDialog from './MemoryUploadDialog.vue'

type MemoryDetailCapabilities = {
  canEdit?: boolean
  canUpload?: boolean
  canCreateChildMemory?: boolean
  canDelete?: boolean
  canShare?: boolean
  canManageCover?: boolean
  canEditMediaMetadata?: boolean
  canDeleteMedia?: boolean
  canDeleteCurrentMemory?: boolean
  showScope?: boolean
}

const props = defineProps<{
  tenantName: string
  memory: Memory
  childMemories: Memory[]
  media: MemoryMedia[]
  capabilities?: MemoryDetailCapabilities
  saveMemory?: (memoryId: string, request: UpdateMemoryRequest) => Promise<Memory>
  setCover?: (memoryId: string, request: SetMemoryCoverRequest) => Promise<Memory>
  removeCover?: (memoryId: string) => Promise<Memory>
  uploadMedia?: (memoryId: string, request: UploadMemoryMediaRequest) => Promise<MemoryMedia>
  deleteMemory?: (memoryId: string) => Promise<void>
  updateMediaMetadata?: (
    mediaId: string,
    memoryId: string,
    request: UpdateMemoryMediaMetadataRequest
  ) => Promise<MemoryMedia>
  deleteMedia?: (mediaId: string, memoryId: string) => Promise<void>
}>()

defineEmits<{
  openMemory: [memory: Memory]
  createChildMemory: []
  changed: [memory: Memory]
  deleted: [parentPath: string]
  mediaDeleted: [media: MemoryMedia]
}>()

const editOpen = ref(false)
const coverOpen = ref(false)
const deleteOpen = ref(false)
const shareOpen = ref(false)
const uploadOpen = ref(false)
const capabilities = computed(() => ({
  canEdit: props.capabilities?.canEdit ?? true,
  canUpload: props.capabilities?.canUpload ?? false,
  canCreateChildMemory: props.capabilities?.canCreateChildMemory ?? true,
  canDelete: props.capabilities?.canDelete ?? true,
  canShare: props.capabilities?.canShare ?? true,
  canManageCover: props.capabilities?.canManageCover ?? true,
  canEditMediaMetadata: props.capabilities?.canEditMediaMetadata ?? true,
  canDeleteMedia: props.capabilities?.canDeleteMedia ?? false,
  canDeleteCurrentMemory: props.capabilities?.canDeleteCurrentMemory ?? true,
  showScope: props.capabilities?.showScope ?? true
}))

const coverMedia = computed(() =>
  props.memory.coverMediaId
    ? props.media.find((media) => media.id === props.memory.coverMediaId) || props.media[0]
    : props.media[0]
)
const coverUrl = computed(() => {
  if (props.memory.coverUrl) return `${API_BASE}${props.memory.coverUrl}`
  const media = coverMedia.value
  if (!media) return ''
  return `${API_BASE}${media.previewUrl || media.contentUrl}`
})
const scopeLabel = computed(() => {
  if (props.memory.scope?.type === 'GROUP') return `@${props.memory.scope.groupName}`
  if (props.memory.scope?.type === 'USER') return 'Persönlich'
  return 'Tenant'
})
const parentPath = computed(() => {
  const segments = props.memory.path.split('/').filter(Boolean)
  if (segments.length <= 1) return '/'
  return `/${segments.slice(0, -1).join('/')}`
})
const dateRangeLabel = computed(() => {
  if (props.memory.startDate === props.memory.endDate) return formatDate(props.memory.startDate)
  return `${formatDate(props.memory.startDate)} - ${formatDate(props.memory.endDate)}`
})

function formatDate(value: string): string {
  return new Intl.DateTimeFormat('de-DE', { dateStyle: 'medium' }).format(new Date(value))
}
</script>

<template>
  <div class="memory-detail">
    <header class="memory-detail-header">
      <div class="memory-detail-cover">
        <MemoryCoverImage
          v-if="coverUrl"
          :src="coverUrl"
          :alt="memory.displayName"
          :crop="memory.coverCrop"
        />
        <span v-else class="pi pi-images" />
      </div>
      <div class="memory-detail-heading">
        <div class="memory-detail-title-row">
          <div>
            <p>Memory</p>
            <h1>{{ memory.displayName }}</h1>
          </div>
          <div class="memory-detail-actions">
            <Button
              v-if="capabilities.canUpload"
              icon="pi pi-upload"
              label="Hochladen"
              severity="secondary"
              outlined
              size="small"
              @click="uploadOpen = true"
            />
            <Button
              v-if="capabilities.canEdit"
              icon="pi pi-pencil"
              label="Bearbeiten"
              severity="secondary"
              outlined
              size="small"
              @click="editOpen = true"
            />
            <Button
              v-if="capabilities.canManageCover"
              icon="pi pi-image"
              label="Cover"
              severity="secondary"
              outlined
              size="small"
              @click="coverOpen = true"
            />
            <Button
              v-if="capabilities.canShare"
              icon="pi pi-share-alt"
              label="Teilen"
              severity="secondary"
              outlined
              size="small"
              @click="shareOpen = true"
            />
            <Button
              v-if="capabilities.canDelete && capabilities.canDeleteCurrentMemory"
              icon="pi pi-trash"
              label="Löschen"
              severity="danger"
              outlined
              size="small"
              @click="deleteOpen = true"
            />
          </div>
        </div>
        <div class="memory-detail-meta">
          <span v-if="capabilities.showScope"><i class="pi pi-folder" /> {{ scopeLabel }}</span>
          <span><i class="pi pi-images" /> {{ memory.mediaCount }} Medien</span>
          <span><i class="pi pi-sitemap" /> {{ memory.childCount }} Unter-Memories</span>
        </div>
      </div>
    </header>

    <Tabs value="gallery" class="memory-tabs">
      <TabList>
        <Tab value="gallery">Galerie</Tab>
        <Tab value="children">Unter-Memories</Tab>
        <Tab value="info">Info</Tab>
      </TabList>
      <TabPanels>
        <TabPanel value="gallery">
          <MediaGrid
            :tenant-name="tenantName"
            :media="media"
            :streaming="false"
            :can-edit-metadata="capabilities.canEditMediaMetadata"
            :can-delete-media="capabilities.canDeleteMedia"
            :update-metadata="updateMediaMetadata"
            :delete-media="deleteMedia"
            @deleted="$emit('mediaDeleted', $event)"
          />
        </TabPanel>
        <TabPanel value="children">
          <div class="memory-card-grid memory-card-grid-memories">
            <MemoryCard
              v-for="child in childMemories"
              :key="child.id"
              :memory="child"
              @open="$emit('openMemory', $event)"
            />
            <button
              v-if="capabilities.canCreateChildMemory"
              class="memory-create-card"
              type="button"
              @click="$emit('createChildMemory')"
            >
              <span class="pi pi-plus" />
              <strong>Unter-Memory erstellen</strong>
            </button>
          </div>
        </TabPanel>
        <TabPanel value="info">
          <dl class="memory-info-list">
            <dt>Zeitraum</dt>
            <dd>{{ dateRangeLabel }}</dd>
            <dt>Permalink</dt>
            <dd>{{ memory.path }}</dd>
            <dt>Medien</dt>
            <dd>{{ memory.mediaCount }}</dd>
            <dt>Unter-Memories</dt>
            <dd>{{ memory.childCount }}</dd>
            <template v-if="capabilities.showScope">
              <dt>Bereich</dt>
              <dd>{{ scopeLabel }}</dd>
            </template>
          </dl>
        </TabPanel>
      </TabPanels>
    </Tabs>

    <EditMemoryDialog
      v-model:visible="editOpen"
      :tenant-name="tenantName"
      :memory="memory"
      :save-memory="saveMemory"
      @saved="$emit('changed', $event)"
    />
    <MemoryCoverDialog
      v-model:visible="coverOpen"
      :tenant-name="tenantName"
      :memory="memory"
      :media="media"
      :set-cover-override="setCover"
      :remove-cover-override="removeCover"
      @saved="$emit('changed', $event)"
    />
    <DeleteMemoryDialog
      v-model:visible="deleteOpen"
      :tenant-name="tenantName"
      :memory="memory"
      :delete-memory-override="deleteMemory"
      @deleted="$emit('deleted', parentPath)"
    />
    <MemoryShareDialog v-model:visible="shareOpen" :tenant-name="tenantName" :memory="memory" />
    <MemoryUploadDialog
      v-model:visible="uploadOpen"
      :tenant-name="tenantName"
      :memory-id="memory.id"
      :upload-media="uploadMedia"
      @saved="$emit('changed', memory)"
    />
  </div>
</template>
