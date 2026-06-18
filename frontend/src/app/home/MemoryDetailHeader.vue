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
  <img v-if="heroUrl" class="detail-hero" :src="heroUrl" alt="" />
  <div class="detail-head">
    <button v-if="showBack" class="crumb" @click="emit('back')">{{ t('common.back') }}</button>
    <nav v-if="crumbs.length > 1" class="crumbs detail-crumbs">
      <template v-for="(c, i) in crumbs" :key="c.id">
        <span v-if="i > 0" class="crumb-sep">/</span>
        <button class="crumb" :class="{ current: i === crumbs.length - 1 }" @click="emit('navigate', c.id)">{{ c.name }}</button>
      </template>
    </nav>
    <h1 class="detail-title">{{ currentName }}</h1>
    <span class="content-count">{{ t('detail.mediaCount', { n: mediaCount }) }}</span>
    <slot />
  </div>
</template>
