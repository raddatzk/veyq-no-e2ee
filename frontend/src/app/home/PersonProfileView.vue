<script setup lang="ts">
import { computed } from 'vue'
import { t } from '../../i18n/i18n'
import type { ParticipantNodeHit } from '../../features/people/participants/participants'

const props = defineProps<{
  name: string
  hits: ParticipantNodeHit[]
  covers: Record<string, string | null>
  loading?: boolean
  error?: string
}>()

const emit = defineEmits<{
  back: []
  openMemory: [hit: ParticipantNodeHit]
}>()

const initials = computed(() => props.name.split(/\s+/).filter(Boolean).slice(0, 2).map((part) => part[0]?.toUpperCase()).join('') || '?')
</script>

<template>
  <section class="person-profile">
    <header class="person-profile-header">
      <button class="crumb detail-back" type="button" @click="emit('back')">{{ t('common.back') }}</button>
      <div class="person-avatar" aria-hidden="true">{{ initials }}</div>
      <div class="person-profile-copy">
        <h1 class="person-profile-title">{{ name }}</h1>
        <div class="person-profile-meta">{{ t('people.profileMemoryCount', { n: hits.length }) }}</div>
      </div>
    </header>

    <div v-if="loading" class="home-empty">{{ t('app.loading') }}</div>
    <div v-else-if="error" class="home-empty">{{ error }}</div>
    <div v-else-if="!hits.length" class="home-empty">{{ t('people.profileEmpty') }}</div>
    <div v-else class="card-grid card-grid-memories person-memory-grid">
      <button v-for="hit in hits" :key="hit.id" class="memory-card person-memory-card" type="button" @click="emit('openMemory', hit)">
        <img v-if="covers[hit.id]" class="memory-cover" :src="covers[hit.id]!" alt="" />
        <div v-else class="memory-cover placeholder">...</div>
        <div class="memory-label">
          <div class="memory-name">{{ hit.name }}</div>
          <span class="person-memory-type">{{ hit.type === 'CHAPTER' ? t('people.chapter') : t('people.memory') }}</span>
        </div>
      </button>
    </div>
  </section>
</template>
