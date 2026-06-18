<script setup lang="ts">
import { t } from '../../i18n/i18n'
import type { Bucket } from '../../features/memory/buckets/buckets'

defineProps<{
  dateRange?: string | null
  selectedBucket?: Bucket | null
  bucketLabel?: (bucket: Bucket) => string
  cameraFilter?: string | null
  cameraOptions: string[]
  hasHighlights: boolean
  showHighlights: boolean
}>()

const emit = defineEmits<{
  clearBucket: []
  cameraFilterChange: [value: string | null]
  toggleHighlights: []
}>()

function onCameraFilterChange(event: Event) {
  const value = (event.target as HTMLSelectElement).value
  emit('cameraFilterChange', value || null)
}
</script>

<template>
  <div class="detail-gallery-bar">
    <span v-if="dateRange" class="detail-daterange">{{ dateRange }}</span>
    <button v-if="selectedBucket" class="btn btn-ghost gallery-filter active" type="button" @click="emit('clearBucket')">
      {{ bucketLabel?.(selectedBucket) || t('contrib.member') }} ✕
    </button>
    <select v-if="selectedBucket && cameraOptions.length" :value="cameraFilter ?? ''" class="bucket-camera-filter" @change="onCameraFilterChange">
      <option value="">{{ t('contrib.allCameras') }}</option>
      <option v-for="camera in cameraOptions" :key="camera" :value="camera">{{ camera }}</option>
    </select>
    <button v-if="hasHighlights" class="btn btn-ghost gallery-filter" :class="{ active: showHighlights }" type="button" @click="emit('toggleHighlights')">
      ★ {{ t('detail.highlights') }}
    </button>
  </div>
</template>
