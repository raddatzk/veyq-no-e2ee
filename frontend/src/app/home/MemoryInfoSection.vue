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
  <dl class="memory-info-list">
    <dt>{{ t('detail.gallery') }}</dt>
    <dd>{{ mediaCount }}</dd>
    <template v-if="dateRange">
      <dt>{{ t('info.dateRange') }}</dt>
      <dd>{{ dateRange }}</dd>
    </template>
    <dt>{{ t('detail.map') }}</dt>
    <dd>{{ trackCount }}</dd>
    <dt>{{ t('info.chapters') }}</dt>
    <dd>{{ chapterCount }}</dd>
  </dl>
</template>
