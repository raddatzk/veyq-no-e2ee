<script setup lang="ts">
import { t } from '../../i18n/i18n'

defineProps<{
  mediaCount: number
  dateRange: string | null
  trackCount: number
  chapterCount: number
}>()
</script>

<template>
  <section class="memory-info">
    <div class="info-row"><span>{{ t('detail.gallery') }}</span><strong>{{ mediaCount }}</strong></div>
    <div v-if="dateRange" class="info-row"><span>{{ t('info.dateRange') }}</span><strong>{{ dateRange }}</strong></div>
    <div class="info-row"><span>{{ t('detail.map') }}</span><strong>{{ trackCount }}</strong></div>
    <div class="info-row"><span>{{ t('info.chapters') }}</span><strong>{{ chapterCount }}</strong></div>
  </section>
</template>
