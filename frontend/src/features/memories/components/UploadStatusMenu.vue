<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import {
  batchProgress,
  cancelBatch,
  cancelFile,
  fileProgress,
  isBatchFinished,
  useUploadQueue,
  type UploadQueueBatch,
  type UploadQueueFile
} from '../composables/useUploadQueue'

const props = defineProps<{
  batches?: UploadQueueBatch[]
  inline?: boolean
}>()

const { t } = useI18n()
const { visibleBatches, activeFileCount, queueRevision, cancelAll, acknowledgeFinished } = useUploadQueue()
const open = ref(false)
const popoverRoot = ref<HTMLElement | null>(null)

const displayedBatches = computed(() => {
  trackQueueRevision()
  return props.batches ?? visibleBatches.value
})
const hasUploads = computed(() => displayedBatches.value.length > 0)
const activeCount = computed(() =>
  displayedBatches.value.flatMap((batch) => batch.files).filter((file) => canCancelFile(file)).length
)
const uploadButtonLabel = computed(() =>
  activeFileCount.value > 0
    ? t('media.uploadActiveCount', { count: activeFileCount.value })
    : t('media.uploads')
)

function toggleOpen() {
  open.value = !open.value
  if (!open.value) acknowledgeFinished()
}

function closeOpenPopover() {
  if (!open.value) return
  open.value = false
  acknowledgeFinished()
}

function handleDocumentPointerDown(event: PointerEvent) {
  if (!open.value) return
  const root = popoverRoot.value
  if (root && event.target instanceof Node && !root.contains(event.target)) {
    closeOpenPopover()
  }
}

function handleDocumentKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') {
    closeOpenPopover()
  }
}

function statusLabel(file: UploadQueueFile): string {
  return t(`media.uploadStatus.${file.status}`)
}

function statusIcon(file: UploadQueueFile): string {
  if (file.status === 'done') return 'pi pi-check'
  if (file.status === 'failed') return 'pi pi-times'
  if (file.status === 'cancelled') return 'pi pi-ban'
  if (file.status === 'uploading') return 'pi pi-spin pi-spinner'
  return 'pi pi-clock'
}

function canCancelFile(file: UploadQueueFile): boolean {
  return file.status === 'queued' || file.status === 'uploading'
}

function cancelVisibleUploads() {
  if (props.batches) props.batches.forEach(cancelBatch)
  else cancelAll()
}

function trackQueueRevision() {
  void queueRevision.value
}

onMounted(() => {
  document.addEventListener('pointerdown', handleDocumentPointerDown)
  document.addEventListener('keydown', handleDocumentKeydown)
})

onBeforeUnmount(() => {
  document.removeEventListener('pointerdown', handleDocumentPointerDown)
  document.removeEventListener('keydown', handleDocumentKeydown)
})
</script>

<template>
  <section v-if="inline" class="upload-status upload-status-inline">
    <header class="upload-status-header">
      <div>
        <h3>{{ t('media.uploads') }}</h3>
        <p>{{ activeCount ? t('media.uploadActiveCount', { count: activeCount }) : t('media.uploadNoActive') }}</p>
      </div>
      <Button
        v-if="activeCount"
        type="button"
        icon="pi pi-times"
        :label="t('media.uploadCancelAll')"
        severity="secondary"
        outlined
        size="small"
        @click="cancelVisibleUploads"
      />
    </header>
    <div v-if="hasUploads" class="upload-status-batches">
      <article v-for="batch in displayedBatches" :key="batch.id" class="upload-status-batch">
        <div class="upload-status-batch-header">
          <strong>{{ batch.label }}</strong>
          <span>{{ t('media.uploadPercent', { percent: batchProgress(batch) }) }}</span>
        </div>
        <div class="memory-upload-progress-track" role="progressbar" :aria-valuenow="batchProgress(batch)" aria-valuemin="0" aria-valuemax="100">
          <span :style="{ width: `${batchProgress(batch)}%` }" />
        </div>
        <ul class="upload-status-file-list">
          <li v-for="file in batch.files" :key="file.id" class="upload-status-file" :class="`upload-status-file-${file.status}`">
            <span class="upload-status-file-icon" :class="statusIcon(file)" />
            <span class="upload-status-file-name">{{ file.name }}</span>
            <span class="upload-status-file-status">{{ statusLabel(file) }}</span>
            <span class="upload-status-file-percent">{{ t('media.uploadPercent', { percent: fileProgress(file) }) }}</span>
            <button
              v-if="canCancelFile(file)"
              type="button"
              class="upload-status-file-cancel"
              :aria-label="t('media.uploadCancelFile', { file: file.name })"
              @click="cancelFile(file)"
            >
              <span class="pi pi-times" />
            </button>
          </li>
        </ul>
      </article>
    </div>
  </section>

  <div v-else ref="popoverRoot" class="upload-status-popover">
    <button
      type="button"
      class="upload-status-trigger"
      :class="{ 'upload-status-trigger-active': activeFileCount > 0 }"
      :aria-label="uploadButtonLabel"
      @click="toggleOpen"
    >
      <span class="pi" :class="activeFileCount > 0 ? 'pi-spin pi-spinner' : 'pi-upload'" />
      <span v-if="activeFileCount" class="upload-status-badge">{{ activeFileCount }}</span>
    </button>
    <section v-if="open" class="upload-status-panel">
      <header class="upload-status-header">
        <div>
          <h3>{{ t('media.uploads') }}</h3>
          <p>{{ activeFileCount ? t('media.uploadActiveCount', { count: activeFileCount }) : t('media.uploadNoActive') }}</p>
        </div>
        <Button
          v-if="activeFileCount"
          type="button"
          icon="pi pi-times"
          :label="t('media.uploadCancelAll')"
          severity="secondary"
          outlined
          size="small"
          @click="cancelAll"
        />
      </header>
      <div v-if="hasUploads" class="upload-status-batches">
        <article v-for="batch in displayedBatches" :key="batch.id" class="upload-status-batch">
          <div class="upload-status-batch-header">
            <strong>{{ batch.label }}</strong>
            <span>{{ t('media.uploadPercent', { percent: batchProgress(batch) }) }}</span>
          </div>
          <div class="memory-upload-progress-track" role="progressbar" :aria-valuenow="batchProgress(batch)" aria-valuemin="0" aria-valuemax="100">
            <span :style="{ width: `${batchProgress(batch)}%` }" />
          </div>
          <ul class="upload-status-file-list">
            <li v-for="file in batch.files" :key="file.id" class="upload-status-file" :class="`upload-status-file-${file.status}`">
              <span class="upload-status-file-icon" :class="statusIcon(file)" />
              <span class="upload-status-file-name">{{ file.name }}</span>
              <span class="upload-status-file-status">{{ statusLabel(file) }}</span>
              <span class="upload-status-file-percent">{{ t('media.uploadPercent', { percent: fileProgress(file) }) }}</span>
              <button
                v-if="canCancelFile(file)"
                type="button"
                class="upload-status-file-cancel"
                :aria-label="t('media.uploadCancelFile', { file: file.name })"
                @click="cancelFile(file)"
              >
                <span class="pi pi-times" />
              </button>
            </li>
          </ul>
          <button
            v-if="isBatchFinished(batch)"
            type="button"
            class="upload-status-dismiss"
            @click="acknowledgeFinished(batch)"
          >
            {{ t('common.close') }}
          </button>
        </article>
      </div>
    </section>
  </div>
</template>
