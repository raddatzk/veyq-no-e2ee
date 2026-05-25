<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import InputText from 'primevue/inputtext'
import { API_BASE } from '../../../shared/api/httpClient'
import type { MemoryMedia } from '../api'

const props = defineProps<{
  visible: boolean
  media: MemoryMedia[]
  caption: string
  selectedIds: string[]
}>()

const emit = defineEmits<{
  'update:visible': [visible: boolean]
  'update:caption': [caption: string]
  captionInput: []
  toggle: [mediaId: string]
  cancel: []
  confirm: []
}>()

const { locale, t } = useI18n()

const dialogVisible = computed({
  get: () => props.visible,
  set: (value: boolean) => emit('update:visible', value)
})

function isVideoMedia(media: MemoryMedia): boolean {
  return media.mimeType.startsWith('video/')
}

function mediaPickerThumbnail(media: MemoryMedia): string {
  return `${API_BASE}${media.previewUrl || media.contentUrl}`
}

function mediaPickerDate(media: MemoryMedia): string {
  return new Intl.DateTimeFormat(locale.value === 'en' ? 'en-US' : 'de-DE', { dateStyle: 'medium' })
    .format(new Date((media.capturedAt || media.createdAt).slice(0, 10)))
}
</script>

<template>
  <Dialog
    v-model:visible="dialogVisible"
    :header="t('memory.journalMediaPickerTitle')"
    modal
    class="memory-journal-media-dialog"
    @hide="$emit('cancel')"
  >
    <div class="memory-journal-media-picker">
      <p class="memory-dialog-copy">{{ t('memory.journalMediaPickerCopy') }}</p>
      <label class="memory-journal-field">
        <span>{{ t('memory.journalMediaCaption') }}</span>
        <InputText
          :model-value="caption"
          :placeholder="t('memory.journalMediaCaptionPlaceholder')"
          @update:model-value="$emit('update:caption', $event ?? '')"
          @input="$emit('captionInput')"
        />
      </label>
      <div v-if="media.length" class="memory-journal-media-picker-grid">
        <button
          v-for="item in media"
          :key="item.id"
          type="button"
          class="memory-journal-media-option"
          :class="{ 'memory-journal-media-option-selected': selectedIds.includes(item.id) }"
          @click="$emit('toggle', item.id)"
        >
          <span class="memory-journal-media-option-thumb">
            <img
              v-if="!isVideoMedia(item)"
              :src="mediaPickerThumbnail(item)"
              :alt="item.title || item.id"
            />
            <video v-else :src="mediaPickerThumbnail(item)" muted playsinline />
          </span>
          <span class="memory-journal-media-option-body">
            <strong>{{ item.title || item.id }}</strong>
            <small>{{ item.mimeType }} · {{ mediaPickerDate(item) }}</small>
          </span>
          <span class="memory-journal-media-option-check">
            <span v-if="selectedIds.includes(item.id)" class="pi pi-check" />
          </span>
        </button>
      </div>
      <div v-else class="memory-journal-empty">
        <span class="pi pi-images" />
        <p>{{ t('memory.journalMediaPickerEmpty') }}</p>
      </div>
    </div>
    <template #footer>
      <div class="memory-journal-editor-actions">
        <Button
          icon="pi pi-times"
          :label="t('common.cancel')"
          severity="secondary"
          outlined
          @click="$emit('cancel')"
        />
        <Button
          icon="pi pi-check"
          :label="t('memory.journalInsertMedia')"
          :disabled="selectedIds.length === 0"
          @click="$emit('confirm')"
        />
      </div>
    </template>
  </Dialog>
</template>
