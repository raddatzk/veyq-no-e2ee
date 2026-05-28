<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import FileUpload, { type FileUploadSelectEvent } from 'primevue/fileupload'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import Select from 'primevue/select'
import { groups, type TenantGroup } from '../../../api/groupClient'
import { toPermalink } from '../../../composables/usePermalink'
import IsoDateField from '../../../shared/components/IsoDateField.vue'
import { useAuthState } from '../../../shared/state/useAuthState'
import { getErrorMessage } from '../../../shared/utils/errors'
import { createMemory, setMemoryCover, type CreateMemoryRequest, type Memory, type SetMemoryCoverRequest } from '../api'
import { isValidMemoryDateRange } from '../utils/memoryDateRange'

const visible = defineModel<boolean>('visible', { required: true })
const props = defineProps<{
  tenantName: string
  tenantDisplayName: string
  folderPath?: string
  parentMemoryId?: string | null
  createMemoryOverride?: (request: CreateMemoryRequest) => Promise<Memory>
  setCoverOverride?: (memoryId: string, request: SetMemoryCoverRequest) => Promise<Memory>
}>()
const emit = defineEmits<{
  saved: [memory: Memory]
}>()

const { t } = useI18n()
const { authState } = useAuthState()
const cropWidth = 320
const cropHeight = 180
const displayName = ref('')
const name = ref('')
const startDate = ref('')
const endDate = ref('')
const permalinkTouched = ref(false)
const error = ref('')
const loading = ref(false)
const availableGroups = ref<TenantGroup[]>([])
const scopeValue = ref('TENANT')
const selectedCoverFile = ref<File | null>(null)
const coverSourceUrl = ref('')
const coverImageElement = ref<HTMLImageElement | null>(null)
const naturalWidth = ref(0)
const naturalHeight = ref(0)
const zoom = ref(1)
const offsetX = ref(0)
const offsetY = ref(0)
const dragging = ref(false)
const dragStart = ref({ pointerId: 0, x: 0, y: 0, offsetX: 0, offsetY: 0 })
const isChildMemory = computed(() => Boolean(props.parentMemoryId))
const isTenantRootCreate = computed(() => !isChildMemory.value && (!props.folderPath || props.folderPath === '/'))
const title = computed(() => (isChildMemory.value ? t('memory.childCreate') : t('memory.create')))
const imageLoaded = computed(() => naturalWidth.value > 0 && naturalHeight.value > 0)
const canSubmit = computed(() =>
  Boolean(displayName.value.trim() && name.value.trim() && isValidMemoryDateRange(startDate.value, endDate.value))
)
const baseScale = computed(() => {
  if (!naturalWidth.value || !naturalHeight.value) return 1
  return Math.max(cropWidth / naturalWidth.value, cropHeight / naturalHeight.value)
})
const baseImageWidth = computed(() => naturalWidth.value * baseScale.value)
const baseImageHeight = computed(() => naturalHeight.value * baseScale.value)
const displayedCoverStyle = computed(() => ({
  width: `${baseImageWidth.value * zoom.value}px`,
  height: `${baseImageHeight.value * zoom.value}px`,
  transform: `translate(${offsetX.value}px, ${offsetY.value}px)`
}))
const scopeOptions = computed(() => [
  { label: props.tenantDisplayName, value: 'TENANT' },
  ...availableGroups.value.map((group) => ({
    label: `@${group.displayName}`,
    value: `GROUP:${group.name}`
  })),
  { label: `~${authState.user?.displayName || 'me'}`, value: 'USER' }
])

watch(displayName, (value) => {
  if (!permalinkTouched.value) name.value = toPermalink(value)
})

watch(visible, (isVisible) => {
  if (!isVisible) return
  displayName.value = ''
  name.value = ''
  startDate.value = ''
  endDate.value = ''
  permalinkTouched.value = false
  error.value = ''
  scopeValue.value = 'TENANT'
  resetCover()
  if (isTenantRootCreate.value) void loadGroups()
})

onBeforeUnmount(resetCover)

function updatePermalink(value: string | undefined) {
  permalinkTouched.value = true
  name.value = toPermalink(value || '')
}

async function submit() {
  const request = {
    displayName: displayName.value.trim(),
    name: name.value.trim(),
    startDate: startDate.value,
    endDate: endDate.value,
    parentId: props.parentMemoryId || null,
    folderPath: props.parentMemoryId ? null : props.folderPath || '/',
    scope: rootScopeRequest()
  }
  if (!request.name || !request.displayName || !isValidMemoryDateRange(request.startDate, request.endDate)) return

  error.value = ''
  loading.value = true
  try {
    const created = props.createMemoryOverride
      ? await props.createMemoryOverride(request)
      : await createMemory(props.tenantName, request)
    if (selectedCoverFile.value && imageLoaded.value) {
      const saveCover = props.setCoverOverride
        ? props.setCoverOverride
        : (memoryId: string, coverRequest: SetMemoryCoverRequest) =>
            setMemoryCover(props.tenantName, memoryId, coverRequest)
      await saveCover(created.id, {
        file: selectedCoverFile.value,
        ...currentCoverCrop()
      })
    }
    visible.value = false
    emit('saved', created)
  } catch (unknownError) {
    error.value = getErrorMessage(unknownError, t('memory.createFailed'))
  } finally {
    loading.value = false
  }
}

async function loadGroups() {
  try {
    availableGroups.value = await groups(props.tenantName)
  } catch {
    availableGroups.value = []
  }
}

function rootScopeRequest() {
  if (!isTenantRootCreate.value) return null
  if (scopeValue.value === 'USER') return { type: 'USER' as const }
  if (scopeValue.value.startsWith('GROUP:')) {
    return { type: 'GROUP' as const, groupName: scopeValue.value.substring('GROUP:'.length) }
  }
  return { type: 'TENANT' as const }
}

function selectCover(event: FileUploadSelectEvent) {
  const file = event.files[0] || null
  if (!file) return
  resetCover()
  selectedCoverFile.value = file
  coverSourceUrl.value = URL.createObjectURL(file)
}

async function loadCoverImage() {
  const image = coverImageElement.value
  if (!image) return
  naturalWidth.value = image.naturalWidth
  naturalHeight.value = image.naturalHeight
  await nextTick()
  centerImage()
}

function resetCover() {
  if (coverSourceUrl.value.startsWith('blob:')) URL.revokeObjectURL(coverSourceUrl.value)
  selectedCoverFile.value = null
  coverSourceUrl.value = ''
  naturalWidth.value = 0
  naturalHeight.value = 0
  zoom.value = 1
  offsetX.value = 0
  offsetY.value = 0
}

function centerImage() {
  offsetX.value = (cropWidth - baseImageWidth.value * zoom.value) / 2
  offsetY.value = (cropHeight - baseImageHeight.value * zoom.value) / 2
  clampOffset()
}

function updateZoom(event: Event) {
  const target = event.target as HTMLInputElement
  const nextZoom = Number(target.value)
  const previousZoom = zoom.value
  if (!Number.isFinite(nextZoom) || nextZoom <= 0 || previousZoom <= 0) return
  const centerX = cropWidth / 2
  const centerY = cropHeight / 2
  offsetX.value = centerX - ((centerX - offsetX.value) / previousZoom) * nextZoom
  offsetY.value = centerY - ((centerY - offsetY.value) / previousZoom) * nextZoom
  zoom.value = nextZoom
  clampOffset()
}

function startDrag(event: PointerEvent) {
  if (!imageLoaded.value) return
  dragging.value = true
  dragStart.value = {
    pointerId: event.pointerId,
    x: event.clientX,
    y: event.clientY,
    offsetX: offsetX.value,
    offsetY: offsetY.value
  }
  ;(event.currentTarget as HTMLElement).setPointerCapture(event.pointerId)
}

function drag(event: PointerEvent) {
  if (!dragging.value || event.pointerId !== dragStart.value.pointerId) return
  offsetX.value = dragStart.value.offsetX + event.clientX - dragStart.value.x
  offsetY.value = dragStart.value.offsetY + event.clientY - dragStart.value.y
  clampOffset()
}

function stopDrag(event: PointerEvent) {
  if (!dragging.value || event.pointerId !== dragStart.value.pointerId) return
  dragging.value = false
  ;(event.currentTarget as HTMLElement).releasePointerCapture(event.pointerId)
}

function clampOffset() {
  const width = baseImageWidth.value * zoom.value
  const height = baseImageHeight.value * zoom.value
  offsetX.value = clamp(offsetX.value, Math.min(0, cropWidth - width), 0)
  offsetY.value = clamp(offsetY.value, Math.min(0, cropHeight - height), 0)
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value))
}

function currentCoverCrop() {
  const scale = baseScale.value * zoom.value
  return {
    cropX: clamp(-offsetX.value / scale / naturalWidth.value, 0, 1),
    cropY: clamp(-offsetY.value / scale / naturalHeight.value, 0, 1),
    cropWidth: clamp(cropWidth / scale / naturalWidth.value, 0, 1),
    cropHeight: clamp(cropHeight / scale / naturalHeight.value, 0, 1)
  }
}
</script>

<template>
  <Dialog v-model:visible="visible" :header="title" modal class="tenant-dialog">
    <form id="create-memory-form" class="veyq-form" @submit.prevent="submit">
      <label>
        {{ t('common.name') }}
        <InputText v-model="displayName" autocomplete="off" required />
      </label>
      <label>
        {{ t('common.permalink') }}
        <InputText
          :model-value="name"
          autocomplete="off"
          required
          @update:model-value="updatePermalink"
        />
      </label>
      <label>
        {{ t('memory.startDate') }}
        <IsoDateField v-model="startDate" :aria-label="t('memory.startDate')" required />
      </label>
      <label>
        {{ t('memory.endDate') }}
        <IsoDateField v-model="endDate" :aria-label="t('memory.endDate')" required />
      </label>
      <label v-if="isTenantRootCreate">
        {{ t('memory.area') }}
        <Select
          v-model="scopeValue"
          :options="scopeOptions"
          option-label="label"
          option-value="value"
          class="scope-select"
        />
      </label>
      <div class="memory-dialog-cover">
        <span>{{ t('memory.cover') }}</span>
        <FileUpload
          mode="basic"
          accept="image/*"
          :choose-label="t('memory.coverChooseFile')"
          custom-upload
          @select="selectCover"
        />
      </div>
      <div v-if="coverSourceUrl" class="memory-cover-editor">
        <div
          class="memory-cover-cropper"
          :class="{ 'memory-cover-cropper-dragging': dragging }"
          :style="{ width: `${cropWidth}px`, aspectRatio: `${cropWidth} / ${cropHeight}` }"
          @pointerdown="startDrag"
          @pointermove="drag"
          @pointerup="stopDrag"
          @pointercancel="stopDrag"
        >
          <img
            ref="coverImageElement"
            :src="coverSourceUrl"
            :alt="selectedCoverFile?.name || t('memory.cover')"
            :style="displayedCoverStyle"
            draggable="false"
            @load="loadCoverImage"
          />
        </div>
        <label class="memory-cover-zoom">
          <span>{{ t('memory.zoom') }}</span>
          <input
            type="range"
            min="1"
            max="3"
            step="0.01"
            :value="zoom"
            :disabled="!imageLoaded"
            @input="updateZoom"
          />
        </label>
      </div>
      <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>
    </form>
    <template #footer>
      <div class="veyq-dialog-action-row">
        <Button
          type="button"
          :label="t('common.cancel')"
          icon="pi pi-times"
          severity="secondary"
          outlined
          @click="visible = false"
        />
        <Button
          type="submit"
          form="create-memory-form"
          :label="t('common.create')"
          icon="pi pi-plus"
          :disabled="!canSubmit"
          :loading="loading"
        />
      </div>
    </template>
  </Dialog>
</template>
