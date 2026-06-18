<script setup lang="ts">
import { t } from '../../i18n/i18n'

defineProps<{
  loading: boolean
  hasTiles: boolean
  hasChildren: boolean
}>()
</script>

<template>
  <div v-if="loading" class="home-empty">{{ t('detail.loadingGallery') }}</div>
  <div v-else-if="!hasTiles && !hasChildren" class="home-empty">{{ t('detail.noMedia') }}</div>
</template>
