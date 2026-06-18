<script setup lang="ts">
import { t } from '../../i18n/i18n'

export type MemoryDetailTab = 'gallery' | 'map' | 'journal' | 'info' | 'contributions' | 'chapters' | 'playlists'

defineProps<{
  tab: MemoryDetailTab
  hasChildren: boolean
  hasBuckets: boolean
  hasPlaylists: boolean
}>()

const emit = defineEmits<{
  open: [tab: MemoryDetailTab]
}>()
</script>

<template>
  <div class="detail-tabs">
    <button class="detail-tab" :class="{ active: tab === 'gallery' }" @click="emit('open', 'gallery')">{{ t('detail.gallery') }}</button>
    <button v-if="hasChildren" class="detail-tab" :class="{ active: tab === 'chapters' }" @click="emit('open', 'chapters')">{{ t('detail.chapters') }}</button>
    <button class="detail-tab" :class="{ active: tab === 'map' }" @click="emit('open', 'map')">{{ t('detail.map') }}</button>
    <button class="detail-tab" :class="{ active: tab === 'journal' }" @click="emit('open', 'journal')">{{ t('detail.journal') }}</button>
    <button v-if="hasBuckets" class="detail-tab" :class="{ active: tab === 'contributions' }" @click="emit('open', 'contributions')">{{ t('detail.contributions') }}</button>
    <button v-if="hasPlaylists" class="detail-tab" :class="{ active: tab === 'playlists' }" @click="emit('open', 'playlists')">{{ t('detail.playlists') }}</button>
    <button class="detail-tab" :class="{ active: tab === 'info' }" @click="emit('open', 'info')">{{ t('detail.info') }}</button>
  </div>
</template>
