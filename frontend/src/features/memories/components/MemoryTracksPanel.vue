<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import FileUpload, { type FileUploadSelectEvent } from 'primevue/fileupload'
import InputText from 'primevue/inputtext'
import Select from 'primevue/select'
import ConfirmationDialog from '../../../shared/components/ConfirmationDialog.vue'
import type {
  MemoryTrack,
  MemoryTrackActivityType,
  UpdateMemoryTrackRequest,
  UploadMemoryTrackRequest
} from '../api'
import TrackMap from './TrackMap.vue'

const props = defineProps<{
  tracks: MemoryTrack[]
  canEdit: boolean
  uploadTrack?: (request: UploadMemoryTrackRequest) => Promise<MemoryTrack>
  updateTrack?: (trackId: string, request: UpdateMemoryTrackRequest) => Promise<MemoryTrack>
  deleteTrack?: (trackId: string) => Promise<void>
}>()

const emit = defineEmits<{
  changed: []
}>()

const { t } = useI18n()
const uploadOpen = ref(false)
const editTrack = ref<MemoryTrack | null>(null)
const deleteCandidate = ref<MemoryTrack | null>(null)
const selectedFile = ref<File | null>(null)
const title = ref('')
const activityType = ref<MemoryTrackActivityType>('HIKING')
const saving = ref(false)
const error = ref('')
const deleteConfirmation = ref('')

const activityOptions = computed(() =>
  ACTIVITY_TYPES.map((value) => ({ label: t(`track.activity.${value}`), value }))
)
const canConfirmDelete = computed(
  () => Boolean(deleteCandidate.value) &&
    deleteConfirmation.value.trim() === deleteCandidate.value.title.trim() &&
    !saving.value
)

watch(uploadOpen, (open) => {
  if (!open) resetForm()
})

watch(editTrack, (track) => {
  error.value = ''
  title.value = track?.title ?? ''
  activityType.value = track?.activityType ?? 'HIKING'
})

watch(deleteCandidate, () => {
  error.value = ''
  deleteConfirmation.value = ''
})

function selectTrackFile(event: FileUploadSelectEvent) {
  selectedFile.value = event.files[0] ?? null
  if (selectedFile.value && !title.value.trim()) {
    title.value = selectedFile.value.name.replace(/\.gpx$/i, '')
  }
}

async function submitUpload() {
  if (!props.uploadTrack || !selectedFile.value) return
  saving.value = true
  error.value = ''
  try {
    await props.uploadTrack({
      file: selectedFile.value,
      title: title.value.trim(),
      activityType: activityType.value
    })
    uploadOpen.value = false
    emit('changed')
  } catch {
    error.value = t('track.saveFailed')
  } finally {
    saving.value = false
  }
}

async function submitEdit() {
  if (!props.updateTrack || !editTrack.value) return
  saving.value = true
  error.value = ''
  try {
    await props.updateTrack(editTrack.value.id, {
      title: title.value.trim(),
      activityType: activityType.value
    })
    editTrack.value = null
    emit('changed')
  } catch {
    error.value = t('track.saveFailed')
  } finally {
    saving.value = false
  }
}

async function confirmDelete() {
  if (!props.deleteTrack || !deleteCandidate.value || !canConfirmDelete.value) return
  saving.value = true
  error.value = ''
  try {
    await props.deleteTrack(deleteCandidate.value.id)
    deleteCandidate.value = null
    emit('changed')
  } catch {
    error.value = t('track.deleteFailed')
  } finally {
    saving.value = false
  }
}

function resetForm() {
  selectedFile.value = null
  title.value = ''
  activityType.value = 'HIKING'
  saving.value = false
  error.value = ''
}

function formatDistance(meters: number): string {
  if (meters >= 1000) return `${(meters / 1000).toLocaleString(undefined, { maximumFractionDigits: 1 })} km`
  return `${meters.toLocaleString()} m`
}

function formatElevation(meters?: number | null): string {
  if (meters === null || meters === undefined) return t('common.notAvailable')
  return `${meters.toLocaleString()} m`
}

function formatDuration(startedAt?: string | null, endedAt?: string | null): string {
  if (!startedAt || !endedAt) return t('common.notAvailable')
  const started = Date.parse(startedAt)
  const ended = Date.parse(endedAt)
  if (!Number.isFinite(started) || !Number.isFinite(ended) || ended <= started) {
    return t('common.notAvailable')
  }
  const totalMinutes = Math.round((ended - started) / 60_000)
  const hours = Math.floor(totalMinutes / 60)
  const minutes = totalMinutes % 60
  if (hours <= 0) return t('track.durationMinutes', { minutes })
  if (minutes === 0) return t('track.durationHours', { hours })
  return t('track.durationHoursMinutes', { hours, minutes })
}

const ACTIVITY_TYPES: MemoryTrackActivityType[] = [
  'HIKING',
  'WALKING',
  'RUNNING',
  'CYCLING',
  'MOUNTAIN_BIKING',
  'ROAD_CYCLING',
  'GRAVEL_CYCLING',
  'SKIING',
  'WATER',
  'OTHER'
]
</script>

<template>
  <section class="memory-tracks">
    <div class="memory-tracks-toolbar">
      <div>
        <h2>{{ t('track.title') }}</h2>
        <p>{{ t('track.count', { count: tracks.length }) }}</p>
      </div>
      <Button
        v-if="canEdit"
        icon="pi pi-map"
        :label="t('track.upload')"
        size="small"
        @click="uploadOpen = true"
      />
    </div>

    <div v-if="tracks.length === 0" class="veyq-empty veyq-empty-action">
      <span class="pi pi-map" />
      <span>{{ t('track.empty') }}</span>
      <small>{{ t('track.emptyAction') }}</small>
      <Button
        v-if="canEdit"
        icon="pi pi-upload"
        :label="t('track.upload')"
        size="small"
        @click="uploadOpen = true"
      />
    </div>

    <div v-else class="memory-track-grid">
      <article v-for="track in tracks" :key="track.id" class="veyq-card memory-track-card">
        <TrackMap :track="track" />
        <div class="memory-track-card-body">
          <div>
            <p>{{ t(`track.activity.${track.activityType}`) }}</p>
            <h3>{{ track.title }}</h3>
          </div>
          <dl>
            <div>
              <dt>{{ t('track.distance') }}</dt>
              <dd>{{ formatDistance(track.distanceMeters) }}</dd>
            </div>
            <div>
              <dt>{{ t('track.duration') }}</dt>
              <dd>{{ formatDuration(track.startedAt, track.endedAt) }}</dd>
            </div>
            <div>
              <dt>{{ t('track.ascent') }}</dt>
              <dd>{{ formatElevation(track.ascentMeters) }}</dd>
            </div>
            <div>
              <dt>{{ t('track.descent') }}</dt>
              <dd>{{ formatElevation(track.descentMeters) }}</dd>
            </div>
          </dl>
          <div class="memory-track-actions">
            <a :href="track.contentUrl" class="p-button p-button-sm p-button-secondary p-button-outlined">
              <span class="pi pi-download" />
              <span>{{ t('track.gpx') }}</span>
            </a>
            <Button
              v-if="canEdit"
              icon="pi pi-pencil"
              :aria-label="t('common.edit')"
              size="small"
              severity="secondary"
              outlined
              @click="editTrack = track"
            />
            <Button
              v-if="canEdit"
              icon="pi pi-trash"
              :aria-label="t('common.delete')"
              size="small"
              severity="danger"
              outlined
              @click="deleteCandidate = track"
            />
          </div>
        </div>
      </article>
    </div>

    <Dialog v-model:visible="uploadOpen" modal :header="t('track.upload')" class="memory-track-dialog">
      <div class="veyq-form memory-dialog-form">
        <p v-if="error" class="memory-form-error">{{ error }}</p>
        <FileUpload
          mode="basic"
          accept=".gpx,application/gpx+xml,application/xml,text/xml"
          custom-upload
          :choose-label="selectedFile?.name || t('track.chooseFile')"
          :disabled="saving"
          @select="selectTrackFile"
        >
          <template #filelabel />
        </FileUpload>
        <label>
          <span>{{ t('track.name') }}</span>
          <InputText v-model="title" :disabled="saving" />
        </label>
        <label>
          <span>{{ t('track.activityType') }}</span>
          <Select v-model="activityType" :options="activityOptions" option-label="label" option-value="value" :disabled="saving" />
        </label>
        <div class="veyq-dialog-action-row">
          <Button
            icon="pi pi-times"
            :label="t('common.cancel')"
            severity="secondary"
            outlined
            :disabled="saving"
            @click="uploadOpen = false"
          />
          <Button
            icon="pi pi-save"
            :label="t('common.save')"
            :loading="saving"
            :disabled="!selectedFile || !title.trim()"
            @click="submitUpload"
          />
        </div>
      </div>
    </Dialog>

    <Dialog :visible="Boolean(editTrack)" modal :header="t('track.edit')" class="memory-track-dialog" @update:visible="editTrack = null">
      <div class="veyq-form memory-dialog-form">
        <p v-if="error" class="memory-form-error">{{ error }}</p>
        <label>
          <span>{{ t('track.name') }}</span>
          <InputText v-model="title" :disabled="saving" />
        </label>
        <label>
          <span>{{ t('track.activityType') }}</span>
          <Select v-model="activityType" :options="activityOptions" option-label="label" option-value="value" :disabled="saving" />
        </label>
        <div class="veyq-dialog-action-row">
          <Button
            icon="pi pi-times"
            :label="t('common.cancel')"
            severity="secondary"
            outlined
            :disabled="saving"
            @click="editTrack = null"
          />
          <Button
            icon="pi pi-save"
            :label="t('common.save')"
            :loading="saving"
            :disabled="!title.trim()"
            @click="submitEdit"
          />
        </div>
      </div>
    </Dialog>

    <ConfirmationDialog
      :visible="Boolean(deleteCandidate)"
      :title="t('track.delete')"
      :confirm-label="t('common.delete')"
      confirm-icon="pi pi-trash"
      confirm-severity="danger"
      dialog-class="memory-track-dialog"
      form-class="memory-dialog-form"
      :confirm-disabled="!canConfirmDelete"
      :loading="saving"
      @update:visible="(nextVisible) => { if (!nextVisible) deleteCandidate = null }"
      @confirm="confirmDelete"
    >
      <template #default="{ disabled }">
        <p>{{ t('track.deleteConfirm', { name: deleteCandidate?.title }) }}</p>
        <label>
          <span>{{ t('track.deleteNameLabel') }}</span>
          <InputText
            v-model="deleteConfirmation"
            autocomplete="off"
            :placeholder="deleteCandidate?.title"
            :disabled="disabled"
          />
        </label>
        <p v-if="error" class="memory-form-error">{{ error }}</p>
      </template>
    </ConfirmationDialog>
  </section>
</template>
