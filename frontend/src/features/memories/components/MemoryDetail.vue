<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import Message from 'primevue/message'
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
  MemoryJournal,
  MemoryMedia,
  SetMemoryCoverRequest,
  UpdateMemoryMediaMetadataBatchRequest,
  UpdateMemoryJournalRequest,
  UpdateMemoryMediaMetadataRequest,
  UpdateMemoryRequest,
  UploadMemoryMediaRequest
} from '../api'
import DeleteMemoryDialog from './DeleteMemoryDialog.vue'
import EditMemoryDialog from './EditMemoryDialog.vue'
import JournalMediaPickerDialog from './JournalMediaPickerDialog.vue'
import JournalRenderer from './JournalRenderer.vue'
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
  canEditJournal?: boolean
  canDownload?: boolean
}

type MediaInsertRange = {
  start: number
  end: number
  caption?: string
  mediaIds?: string[]
}

const props = defineProps<{
  tenantName: string
  memory: Memory
  header?: MemoryHeader | null
  journal?: MemoryJournal | null
  childMemories: Memory[]
  media: MemoryMedia[]
  capabilities?: MemoryDetailCapabilities
  saveMemory?: (memoryId: string, request: UpdateMemoryRequest) => Promise<Memory>
  saveJournal?: (memoryId: string, request: UpdateMemoryJournalRequest) => Promise<MemoryJournal>
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
  downloadUrl?: (memoryId: string, includeChildren: boolean) => string
}>()
const { locale, t } = useI18n()

const emit = defineEmits<{
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
const downloadOpen = ref(false)
const mediaOverrides = ref<Record<string, MemoryMedia>>({})
const journalEditing = ref(false)
const journalSaving = ref(false)
const journalDraft = ref('')
const journalError = ref('')
const journalTextarea = ref<HTMLTextAreaElement | null>(null)
const mediaPickerOpen = ref(false)
const mediaPickerCaption = ref('')
const mediaPickerCaptionEdited = ref(false)
const selectedJournalMediaIds = ref<string[]>([])
const mediaInsertRange = ref<MediaInsertRange | null>(null)
const lastMediaTriggerPosition = ref<number | null>(null)
const capabilities = computed(() => ({
  canEdit: props.capabilities?.canEdit ?? true,
  canUpload: props.capabilities?.canUpload ?? true,
  canCreateChildMemory: props.capabilities?.canCreateChildMemory ?? true,
  canDelete: props.capabilities?.canDelete ?? true,
  canShare: props.capabilities?.canShare ?? true,
  canManageCover: props.capabilities?.canManageCover ?? true,
  canEditMediaMetadata: props.capabilities?.canEditMediaMetadata ?? true,
  canDeleteMedia: props.capabilities?.canDeleteMedia ?? false,
  canDeleteCurrentMemory: props.capabilities?.canDeleteCurrentMemory ?? true,
  canEditJournal: props.capabilities?.canEditJournal ?? props.capabilities?.canEdit ?? true,
  canDownload: props.capabilities?.canDownload ?? true
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
const canDownloadMemory = computed(() =>
  capabilities.value.canDownload && totalMediaCount.value > 0 && Boolean(props.downloadUrl || props.tenantName)
)
const directDownloadUrl = computed(() => memoryDownloadUrl(false))
const subtreeDownloadUrl = computed(() => memoryDownloadUrl(true))
const canBatchUpdateMediaMetadata = computed(() => Boolean(props.updateMediaMetadataBatch || props.tenantName))
const journalMarkdown = computed(() => props.journal?.markdown ?? '')
const journalHasContent = computed(() => journalMarkdown.value.trim().length > 0)

watch(
  () => props.journal?.markdown,
  (value) => {
    if (!journalEditing.value) journalDraft.value = value ?? ''
  },
  { immediate: true }
)

function formatDate(value: string): string {
  return new Intl.DateTimeFormat(locale.value === 'en' ? 'en-US' : 'de-DE', { dateStyle: 'medium' }).format(new Date(value))
}

function memoryDownloadUrl(includeChildren: boolean): string {
  if (props.downloadUrl) return props.downloadUrl(props.memory.id, includeChildren)
  const tenant = encodeURIComponent(props.tenantName)
  const memoryId = encodeURIComponent(props.memory.id)
  return `${API_BASE}/api/${tenant}/memories/${memoryId}/download?includeChildren=${includeChildren}&locale=${encodeURIComponent(locale.value)}`
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

function startJournalEdit() {
  journalDraft.value = journalMarkdown.value
  journalError.value = ''
  journalEditing.value = true
}

function cancelJournalEdit() {
  journalDraft.value = journalMarkdown.value
  journalError.value = ''
  journalEditing.value = false
  closeMediaPicker()
}

async function submitJournal() {
  if (!props.saveJournal) return
  journalSaving.value = true
  journalError.value = ''
  try {
    await props.saveJournal(props.memory.id, { markdown: journalDraft.value })
    journalEditing.value = false
    emit('changed', props.memory)
  } catch {
    journalError.value = t('memory.journalSaveFailed')
  } finally {
    journalSaving.value = false
  }
}

function toggleJournalMedia(mediaId: string) {
  selectedJournalMediaIds.value = selectedJournalMediaIds.value.includes(mediaId)
    ? selectedJournalMediaIds.value.filter((id) => id !== mediaId)
    : [...selectedJournalMediaIds.value, mediaId]
  syncMediaPickerCaption()
}

function openMediaPicker(range?: MediaInsertRange) {
  mediaInsertRange.value = range ?? currentTextareaRange()
  selectedJournalMediaIds.value = range?.mediaIds?.filter((id) => props.media.some((media) => media.id === id)) ?? []
  mediaPickerCaption.value = range?.caption ?? ''
  mediaPickerCaptionEdited.value = Boolean(range?.caption)
  mediaPickerOpen.value = true
}

function closeMediaPicker() {
  mediaPickerOpen.value = false
  selectedJournalMediaIds.value = []
  mediaPickerCaption.value = ''
  mediaPickerCaptionEdited.value = false
  mediaInsertRange.value = null
}

function currentTextareaRange(): MediaInsertRange {
  const textarea = journalTextarea.value
  const position = textarea?.selectionStart ?? journalDraft.value.length
  return { start: position, end: textarea?.selectionEnd ?? position }
}

function syncMediaPickerCaption() {
  if (mediaPickerCaptionEdited.value) return
  if (selectedJournalMediaIds.value.length === 1) {
    mediaPickerCaption.value = props.media.find((media) => media.id === selectedJournalMediaIds.value[0])?.title || ''
  } else if (selectedJournalMediaIds.value.length > 1) {
    mediaPickerCaption.value = t('memory.journalMediaCarouselCaption')
  } else {
    mediaPickerCaption.value = ''
  }
}

function insertSelectedJournalMedia() {
  if (selectedJournalMediaIds.value.length === 0) return
  const range = mediaInsertRange.value ?? currentTextareaRange()
  const caption = mediaPickerCaption.value.trim() || (
    selectedJournalMediaIds.value.length === 1
      ? props.media.find((media) => media.id === selectedJournalMediaIds.value[0])?.title || 'Caption'
      : t('memory.journalMediaCarouselCaption')
  )
  const markdown = `![${caption}](${`veyq:media/${selectedJournalMediaIds.value.join(',')}`})`
  journalDraft.value = `${journalDraft.value.slice(0, range.start)}${markdown}${journalDraft.value.slice(range.end)}`
  const nextCursor = range.start + markdown.length
  closeMediaPicker()
  window.setTimeout(() => {
    journalTextarea.value?.focus()
    journalTextarea.value?.setSelectionRange(nextCursor, nextCursor)
  })
}

function handleJournalInput(event: Event) {
  const textarea = event.target as HTMLTextAreaElement
  const position = textarea.selectionStart
  const range = mediaTriggerRange(journalDraft.value, position)
  if (!range) return
  if (lastMediaTriggerPosition.value === position) return
  lastMediaTriggerPosition.value = position
  openMediaPicker(range)
}

function handleJournalDoubleClick(event: MouseEvent) {
  const textarea = event.target as HTMLTextAreaElement
  const range = mediaTagRangeAtPosition(journalDraft.value, textarea.selectionStart)
  if (range) openMediaPicker(range)
}

function mediaTriggerRange(markdown: string, position: number): MediaInsertRange | null {
  const beforeCursor = markdown.slice(0, position)
  const match = beforeCursor.match(/(!\[([^\]\n]*)]\()?veyq:media\/$/)
  if (!match) return null
  const end = markdown[position] === ')' ? position + 1 : position
  return {
    start: position - match[0].length,
    end,
    caption: match[2] || undefined
  }
}

function mediaTagRangeAtPosition(markdown: string, position: number): MediaInsertRange | null {
  const mediaTag = /!\[([^\]\n]*)]\(veyq:media\/([^)]+)\)/g
  for (const match of markdown.matchAll(mediaTag)) {
    const start = match.index ?? 0
    const end = start + match[0].length
    if (position < start || position > end) continue
    const mediaIds = match[2].split(',').map((id) => id.trim()).filter(Boolean)
    if (mediaIds.length === 0) return null
    return {
      start,
      end,
      caption: match[1] || undefined,
      mediaIds
    }
  }
  return null
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
              v-if="canDownloadMemory"
              icon="pi pi-download"
              :label="t('memory.download')"
              severity="secondary"
              outlined
              size="small"
              @click="downloadOpen = true"
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
        <Tab value="journal">{{ t('memory.journal') }}</Tab>
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
        <TabPanel value="journal">
          <section class="memory-journal">
            <div class="memory-journal-toolbar">
              <div>
                <h2>{{ t('memory.journal') }}</h2>
                <p>{{ t('memory.journalUpdated', { date: props.journal ? formatDate(props.journal.updatedAt) : '-' }) }}</p>
              </div>
              <div class="memory-journal-actions">
                <Button
                  v-if="capabilities.canEditJournal && journalEditing"
                  icon="pi pi-images"
                  :label="t('memory.journalInsertMedia')"
                  severity="secondary"
                  outlined
                  size="small"
                  @click="openMediaPicker()"
                />
                <Button
                  v-if="capabilities.canEditJournal && !journalEditing"
                  icon="pi pi-pencil"
                  :label="journalHasContent ? t('common.edit') : t('memory.journalStart')"
                  severity="secondary"
                  outlined
                  size="small"
                  @click="startJournalEdit"
                />
              </div>
            </div>

            <Message v-if="journalError" severity="error" :closable="false">{{ journalError }}</Message>

            <div v-if="journalEditing" class="memory-journal-editor">
              <div class="memory-journal-editor-grid">
                <label class="memory-journal-field">
                  <span>{{ t('memory.journalMarkdown') }}</span>
                  <textarea
                    ref="journalTextarea"
                    v-model="journalDraft"
                    rows="14"
                    :placeholder="t('memory.journalPlaceholder')"
                    @input="handleJournalInput"
                    @dblclick="handleJournalDoubleClick"
                  />
                </label>
                <aside class="memory-journal-help" :aria-label="t('memory.journalHelpTitle')">
                  <div>
                    <h3>{{ t('memory.journalHelpTitle') }}</h3>
                    <p>{{ t('memory.journalHelpCopy') }}</p>
                  </div>
                  <div>
                    <h4>{{ t('memory.journalHelpBasics') }}</h4>
                    <ul>
                      <li><code># {{ t('memory.journalHelpHeadingExample') }}</code></li>
                      <li><code>**{{ t('memory.journalHelpStrongExample') }}**</code></li>
                      <li><code>- {{ t('memory.journalHelpListExample') }}</code></li>
                      <li><code>[{{ t('memory.journalHelpLinkLabel') }}](https://example.com)</code></li>
                    </ul>
                  </div>
                  <div>
                    <h4>{{ t('memory.journalHelpEmbeds') }}</h4>
                    <dl>
                      <dt>{{ t('memory.journalHelpMedia') }}</dt>
                      <dd><code>![Caption](veyq:media/mediaId)</code></dd>
                      <dt>{{ t('memory.journalHelpCarousel') }}</dt>
                      <dd><code>![Caption](veyq:media/media1,media2)</code></dd>
                      <dt>{{ t('memory.journalHelpMemory') }}</dt>
                      <dd><code>[Title](veyq:memory/memoryId)</code></dd>
                      <dt>{{ t('memory.journalHelpMemoryPath') }}</dt>
                      <dd><code>[Title](veyq:memory-path/Day%202)</code></dd>
                      <dt>{{ t('memory.journalHelpPlaylist') }}</dt>
                      <dd><code>[Playlist](veyq:playlist?url=...)</code></dd>
                      <dt>{{ t('memory.journalHelpMap') }}</dt>
                      <dd><code>[Place](veyq:map?lat=52.5&amp;lng=13.4&amp;zoom=12)</code></dd>
                      <dt>{{ t('memory.journalHelpTrack') }}</dt>
                      <dd><code>[Track](veyq:track?url=...)</code></dd>
                    </dl>
                    <p>{{ t('memory.journalHelpMediaEdit') }}</p>
                  </div>
                </aside>
              </div>
              <div class="memory-journal-preview">
                <JournalRenderer
                  v-if="journalDraft.trim()"
                  :markdown="journalDraft"
                  :media="media"
                  :child-memories="childMemories"
                  @open-memory="$emit('openMemory', $event)"
                />
                <p v-else class="memory-empty">{{ t('memory.journalEmptyPreview') }}</p>
              </div>
              <div class="memory-journal-editor-actions">
                <Button
                  icon="pi pi-check"
                  :label="t('common.save')"
                  :loading="journalSaving"
                  :disabled="!props.saveJournal"
                  @click="submitJournal"
                />
                <Button
                  icon="pi pi-times"
                  :label="t('common.cancel')"
                  severity="secondary"
                  outlined
                  :disabled="journalSaving"
                  @click="cancelJournalEdit"
                />
              </div>
            </div>

            <JournalRenderer
              v-else-if="journalHasContent"
              :markdown="journalMarkdown"
              :media="media"
              :child-memories="childMemories"
              @open-memory="$emit('openMemory', $event)"
            />

            <div v-else class="memory-journal-empty">
              <span class="pi pi-book" />
              <p>{{ t('memory.journalEmpty') }}</p>
              <Button
                v-if="capabilities.canEditJournal"
                icon="pi pi-pencil"
                :label="t('memory.journalStart')"
                severity="secondary"
                outlined
                size="small"
                @click="startJournalEdit"
              />
            </div>
          </section>
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
    <Dialog
      v-model:visible="downloadOpen"
      modal
      :header="t('memory.download')"
      class="memory-download-dialog"
    >
      <div class="memory-download-options">
        <a class="memory-download-option" :href="directDownloadUrl" @click="downloadOpen = false">
          <span class="pi pi-download" />
          <span>
            <strong>{{ t('memory.downloadDirect') }}</strong>
            <small>{{ t('memory.downloadDirectCopy', { count: detailHeader.directMediaCount }) }}</small>
          </span>
        </a>
        <a class="memory-download-option" :href="subtreeDownloadUrl" @click="downloadOpen = false">
          <span class="pi pi-sitemap" />
          <span>
            <strong>{{ t('memory.downloadSubtree') }}</strong>
            <small>{{ t('memory.downloadSubtreeCopy', { count: totalMediaCount }) }}</small>
          </span>
        </a>
      </div>
    </Dialog>
    <JournalMediaPickerDialog
      v-model:visible="mediaPickerOpen"
      :media="media"
      :caption="mediaPickerCaption"
      :selected-ids="selectedJournalMediaIds"
      @update:caption="mediaPickerCaption = $event"
      @caption-input="mediaPickerCaptionEdited = true"
      @toggle="toggleJournalMedia"
      @cancel="closeMediaPicker"
      @confirm="insertSelectedJournalMedia"
    />
  </div>
</template>
