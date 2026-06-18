<script setup lang="ts">
import { t } from '../../i18n/i18n'
import type { Bucket } from '../../features/memory/buckets/buckets'

defineProps<{
  buckets: Bucket[]
  canCreateBucket: boolean
  bucketLabel?: (bucket: Bucket) => string
  bucketCount: (bucket: Bucket) => number
  isOwnBucket: (bucket: Bucket) => boolean
}>()

const emit = defineEmits<{
  openBucket: [bucket: Bucket]
  renameBucket: [bucket: Bucket]
  deleteBucket: [bucket: Bucket]
  createBucket: []
}>()
</script>

<template>
  <section class="memory-contributions">
    <ul class="bucket-list">
      <li v-for="bucket in buckets" :key="bucket.id" class="bucket-row" :class="{ sub: !bucket.primary }">
        <button class="bucket-open" type="button" @click="emit('openBucket', bucket)">
          <span class="bucket-name">{{ bucket.primary ? '👤' : '📁' }} {{ bucketLabel?.(bucket) || (bucket.primary ? t('contrib.member') : '—') }}</span>
          <span class="bucket-count">{{ bucketCount(bucket) }}</span>
        </button>
        <span v-if="isOwnBucket(bucket)" class="bucket-actions">
          <button v-if="!bucket.primary" class="btn btn-ghost" type="button" @click="emit('renameBucket', bucket)">{{ t('common.rename') }}</button>
          <button v-if="!bucket.primary" class="btn btn-ghost bucket-delete" type="button" @click="emit('deleteBucket', bucket)">{{ t('common.delete') }}</button>
          <button v-if="bucket.primary && canCreateBucket" class="btn btn-ghost" type="button" @click="emit('createBucket')">+ {{ t('contrib.newBucket') }}</button>
        </span>
      </li>
    </ul>
    <div v-if="!buckets.length" class="home-empty">{{ t('contrib.empty') }}</div>
  </section>
</template>
