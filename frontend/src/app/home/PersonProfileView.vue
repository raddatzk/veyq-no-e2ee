<script setup lang="ts">
import { computed } from 'vue'
import { t } from '../../i18n/i18n'
import type { ParticipantNodeHit } from '../../features/people/participants/participants'
import MemoryObjectCard from './MemoryObjectCard.vue'
import VeyqEmptyState from '../ui/VeyqEmptyState.vue'

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

    <VeyqEmptyState v-if="loading" loading class="home-empty">{{ t('app.loading') }}</VeyqEmptyState>
    <VeyqEmptyState v-else-if="error" class="home-empty">{{ error }}</VeyqEmptyState>
    <VeyqEmptyState v-else-if="!hits.length" class="home-empty">{{ t('people.profileEmpty') }}</VeyqEmptyState>
    <div v-else class="card-grid card-grid-memories person-memory-grid">
      <MemoryObjectCard
        v-for="hit in hits"
        :key="hit.id"
        class="person-memory-card"
        :name="hit.name"
        :cover="covers[hit.id]"
        @open="emit('openMemory', hit)"
      >
        <template #meta>
          <span class="person-memory-type">{{ hit.type === 'CHAPTER' ? t('people.chapter') : t('people.memory') }}</span>
        </template>
      </MemoryObjectCard>
    </div>
  </section>
</template>
