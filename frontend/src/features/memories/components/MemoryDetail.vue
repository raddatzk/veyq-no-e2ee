<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Tab from 'primevue/tab'
import TabList from 'primevue/tablist'
import TabPanel from 'primevue/tabpanel'
import TabPanels from 'primevue/tabpanels'
import Tabs from 'primevue/tabs'
import MediaGrid from '../../../components/media/MediaGrid.vue'
import { API_BASE } from '../../../shared/api/httpClient'
import { updateMemoryMediaMetadata, updateMemoryMediaMetadataBatch as updateTenantMediaMetadataBatch } from '../api'
import type {
  Memory,
  MemoryHeader,
  MemoryMedia,
  SetMemoryCoverRequest,
  UpdateMemoryMediaMetadataBatchRequest,
  UpdateMemoryMediaMetadataRequest,
  UpdateMemoryRequest,
  UploadMemoryMediaRequest
} from '../api'
import DeleteMemoryDialog from './DeleteMemoryDialog.vue'
import EditMemoryDialog from './EditMemoryDialog.vue'
import MemoryAssetBuckets from './MemoryAssetBuckets.vue'
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
}

const props = defineProps<{
  tenantName: string
  memory: Memory
  header?: MemoryHeader | null
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
  updateMediaMetadataBatch?: (
    memoryId: string,
    request: UpdateMemoryMediaMetadataBatchRequest
  ) => Promise<MemoryMedia[]>
  deleteMedia?: (mediaId: string, memoryId: string) => Promise<void>
}>()
const { locale, t } = useI18n()

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
const mediaOverrides = ref<Record<string, MemoryMedia>>({})
const capabilities = computed(() => ({
  canEdit: props.capabilities?.canEdit ?? true,
  canUpload: props.capabilities?.canUpload ?? true,
  canCreateChildMemory: props.capabilities?.canCreateChildMemory ?? true,
  canDelete: props.capabilities?.canDelete ?? true,
  canShare: props.capabilities?.canShare ?? true,
  canManageCover: props.capabilities?.canManageCover ?? true,
  canEditMediaMetadata: props.capabilities?.canEditMediaMetadata ?? true,
  canDeleteMedia: props.capabilities?.canDeleteMedia ?? false,
  canDeleteCurrentMemory: props.capabilities?.canDeleteCurrentMemory ?? true
}))

const detailHeader = computed<MemoryHeader>(() => ({
  id: props.memory.id,
  displayName: props.header?.displayName ?? props.memory.displayName,
  path: props.header?.path ?? props.memory.path,
  coverUrl: props.header?.coverUrl ?? props.memory.coverUrl,
  coverCrop: props.header?.coverCrop ?? props.memory.coverCrop,
  startDate: props.header?.startDate ?? props.memory.startDate,
  endDate: props.header?.endDate ?? props.memory.endDate,
  directMediaCount: props.header?.directMediaCount ?? props.memory.mediaCount,
  descendantMediaCount: props.header?.descendantMediaCount ?? 0,
  childMemoryCount: props.header?.childMemoryCount ?? props.memory.childCount,
  scope: props.header?.scope ?? props.memory.scope
}))
const coverUrl = computed(() => {
  if (detailHeader.value.coverUrl) return `${API_BASE}${detailHeader.value.coverUrl}`
  return ''
})
const parentPath = computed(() => {
  const segments = props.memory.path.split('/').filter(Boolean)
  if (segments.length <= 1) return '/'
  return `/${segments.slice(0, -1).join('/')}`
})
const dateRangeLabel = computed(() => {
  if (detailHeader.value.startDate === detailHeader.value.endDate) return formatDate(detailHeader.value.startDate)
  return `${formatDate(detailHeader.value.startDate)} - ${formatDate(detailHeader.value.endDate)}`
})
const totalMediaCount = computed(
  () => detailHeader.value.directMediaCount + detailHeader.value.descendantMediaCount
)
const mediaCountLabel = computed(() => `${totalMediaCount.value} ${t('memory.mediaCount')}`)
const visibleMedia = computed(() => props.media.map((media) => mediaOverrides.value[media.id] || media))
const canBatchUpdateMediaMetadata = computed(() => Boolean(props.updateMediaMetadataBatch || props.tenantName))

function formatDate(value: string): string {
  return new Intl.DateTimeFormat(locale.value === 'en' ? 'en-US' : 'de-DE', { dateStyle: 'medium' }).format(new Date(value))
}

async function updateSingleMediaMetadata(
  mediaId: string,
  memoryId: string,
  request: UpdateMemoryMediaMetadataRequest
): Promise<MemoryMedia> {
  const updated = props.updateMediaMetadata
    ? await props.updateMediaMetadata(mediaId, memoryId, request)
    : await updateMemoryMediaMetadata(props.tenantName, memoryId, mediaId, request)
  mergeUpdatedMedia([updated])
  return updated
}

async function submitMediaMetadataBatch(
  memoryId: string,
  mediaIds: string[],
  capturedAtOffsetSeconds: number
): Promise<MemoryMedia[]> {
  const request = { mediaIds, capturedAtOffsetSeconds }
  if (!props.updateMediaMetadataBatch && !props.tenantName) throw new Error(t('media.batchSaveFailed'))
  const updated = props.updateMediaMetadataBatch
    ? await props.updateMediaMetadataBatch(memoryId, request)
    : await updateTenantMediaMetadataBatch(props.tenantName, memoryId, request)
  mergeUpdatedMedia(updated)
  return updated
}

function mergeUpdatedMedia(updated: MemoryMedia[]) {
  mediaOverrides.value = {
    ...mediaOverrides.value,
    ...Object.fromEntries(updated.map((media) => [media.id, media]))
  }
}
</script>

<template>
  <div class="memory-detail">
    <header class="memory-detail-header">
      <div class="memory-detail-cover">
        <MemoryCoverImage
          v-if="coverUrl"
          :src="coverUrl"
          :alt="detailHeader.displayName"
          :crop="detailHeader.coverCrop"
        />
        <span v-else class="pi pi-images" />
      </div>
      <div class="memory-detail-heading">
        <div class="memory-detail-title-row">
          <div>
            <p>{{ t('memory.title') }}</p>
            <h1>{{ detailHeader.displayName }}</h1>
          </div>
          <div class="memory-detail-actions">
            <Button
              v-if="capabilities.canUpload"
              icon="pi pi-upload"
              :label="t('memory.upload')"
              class="memory-upload-action"
              size="small"
              @click="uploadOpen = true"
            />
            <Button
              v-if="capabilities.canEdit"
              icon="pi pi-pencil"
              :label="t('common.edit')"
              severity="secondary"
              outlined
              size="small"
              @click="editOpen = true"
            />
            <Button
              v-if="capabilities.canManageCover"
              icon="pi pi-image"
              :label="t('memory.cover')"
              severity="secondary"
              outlined
              size="small"
              @click="coverOpen = true"
            />
            <Button
              v-if="capabilities.canShare"
              icon="pi pi-share-alt"
              :label="t('share.title')"
              severity="secondary"
              outlined
              size="small"
              @click="shareOpen = true"
            />
            <Button
              v-if="capabilities.canDelete && capabilities.canDeleteCurrentMemory"
              icon="pi pi-trash"
              :label="t('common.delete')"
              severity="danger"
              outlined
              size="small"
              @click="deleteOpen = true"
            />
          </div>
        </div>
        <div class="memory-detail-meta">
          <span><i class="pi pi-calendar" /> {{ dateRangeLabel }}</span>
          <span><i class="pi pi-images" /> {{ mediaCountLabel }}</span>
          <span v-if="detailHeader.childMemoryCount > 0">
            <i class="pi pi-sitemap" /> {{ detailHeader.childMemoryCount }} {{ t('memory.children') }}
          </span>
        </div>
      </div>
    </header>

    <Tabs value="gallery" class="memory-tabs">
      <TabList>
        <Tab value="gallery">{{ t('memory.gallery') }}</Tab>
        <Tab value="buckets">{{ t('media.buckets') }}</Tab>
        <Tab value="children">{{ t('memory.children') }}</Tab>
        <Tab value="info">{{ t('memory.info') }}</Tab>
      </TabList>
      <TabPanels>
        <TabPanel value="gallery">
          <MediaGrid
            :tenant-name="tenantName"
            :media="visibleMedia"
            :streaming="false"
            :can-edit-metadata="capabilities.canEditMediaMetadata"
            :can-delete-media="capabilities.canDeleteMedia"
            :update-metadata="updateSingleMediaMetadata"
            :delete-media="deleteMedia"
            @deleted="$emit('mediaDeleted', $event)"
          />
        </TabPanel>
        <TabPanel value="buckets">
          <MemoryAssetBuckets
            :tenant-name="tenantName"
            :memory-id="memory.id"
            :media="visibleMedia"
            :update-metadata-batch="canBatchUpdateMediaMetadata ? submitMediaMetadataBatch : undefined"
            @updated="mergeUpdatedMedia"
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
              <strong>{{ t('memory.childCreate') }}</strong>
            </button>
          </div>
        </TabPanel>
        <TabPanel value="info">
          <dl class="memory-info-list">
            <dt>{{ t('memory.dateRange') }}</dt>
            <dd>{{ dateRangeLabel }}</dd>
            <dt>{{ t('common.permalink') }}</dt>
            <dd>{{ detailHeader.path }}</dd>
            <dt>{{ t('memory.mediaCount') }}</dt>
            <dd>{{ totalMediaCount }}</dd>
            <template v-if="detailHeader.childMemoryCount > 0">
              <dt>{{ t('memory.children') }}</dt>
              <dd>{{ detailHeader.childMemoryCount }}</dd>
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
      :media="visibleMedia"
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
