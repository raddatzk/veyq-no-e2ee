<script setup lang="ts">
import { t } from '../../i18n/i18n'
import { PROVIDER_LABEL, type Playlist } from '../../features/memory/playlist/playlist'

defineProps<{
  playlists: Playlist[]
  canAdd: boolean
  canFeature: boolean
  canRemove: boolean
}>()

const emit = defineEmits<{
  add: []
  feature: [playlist: Playlist]
  remove: [playlist: Playlist]
}>()
</script>

<template>
  <section class="memory-playlists">
    <button v-if="canAdd" class="btn btn-primary" type="button" @click="emit('add')">+ {{ t('playlist.add') }}</button>
    <ul v-if="playlists.length" class="playlist-list">
      <li v-for="p in playlists" :key="p.id" class="playlist-row">
        <span class="playlist-provider">{{ PROVIDER_LABEL[p.provider] }}</span>
        <a class="playlist-title" :href="p.url" target="_blank" rel="noopener noreferrer">{{ p.title || p.url }}</a>
        <button v-if="canFeature" class="btn btn-ghost" type="button" :title="t('playlist.featured')" @click="emit('feature', p)">
          {{ p.featured ? '★' : '☆' }}
        </button>
        <button v-if="canRemove" class="btn btn-ghost playlist-delete" type="button" @click="emit('remove', p)">{{ t('common.delete') }}</button>
      </li>
    </ul>
    <div v-else class="home-empty">{{ t('playlist.empty') }}</div>
  </section>
</template>
