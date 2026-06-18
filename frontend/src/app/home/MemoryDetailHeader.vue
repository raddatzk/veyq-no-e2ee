<script setup lang="ts">
import { t } from '../../i18n/i18n'

defineProps<{
  heroUrl: string | null
  showBack?: boolean
  crumbs: { id: string; name: string }[]
  currentName: string
  mediaCount: number
}>()

const emit = defineEmits<{
  back: []
  navigate: [nodeId: string]
}>()
</script>

<template>
  <header class="memory-detail-header">
    <div class="memory-detail-cover" :class="{ 'memory-detail-cover-has-image': Boolean(heroUrl) }">
      <img v-if="heroUrl" :src="heroUrl" alt="" />
      <span v-else class="memory-detail-cover-placeholder">...</span>
    </div>

    <div class="memory-detail-heading">
      <div class="detail-nav-row">
        <button v-if="showBack" class="crumb detail-back" @click="emit('back')">{{ t('common.back') }}</button>
        <nav v-if="crumbs.length > 1" class="crumbs detail-crumbs">
          <template v-for="(c, i) in crumbs" :key="c.id">
            <span v-if="i > 0" class="crumb-sep">/</span>
            <button class="crumb" :class="{ current: i === crumbs.length - 1 }" @click="emit('navigate', c.id)">{{ c.name }}</button>
          </template>
        </nav>
      </div>

      <div class="memory-detail-title-row">
        <div class="memory-detail-title-copy">
          <h1 class="detail-title">{{ currentName }}</h1>
        </div>
        <div class="memory-detail-actions">
          <slot />
        </div>
      </div>

      <div class="memory-detail-meta">
        <span>{{ t('detail.mediaCount', { n: mediaCount }) }}</span>
      </div>
    </div>
  </header>
</template>
