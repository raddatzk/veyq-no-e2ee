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
    <div v-if="canAdd" class="playlist-bar">
      <button class="btn btn-primary playlist-add" type="button" @click="emit('add')">+ {{ t('playlist.add') }}</button>
    </div>
    <ul v-if="playlists.length" class="playlist-list">
      <li v-for="p in playlists" :key="p.id" class="playlist-row" :class="{ 'playlist-row-featured': p.featured }">
        <div class="playlist-main">
          <span class="playlist-provider-mark" aria-hidden="true">{{ PROVIDER_LABEL[p.provider].slice(0, 2) }}</span>
          <span class="playlist-copy">
            <span class="playlist-provider">{{ PROVIDER_LABEL[p.provider] }}</span>
            <a class="playlist-title" :href="p.url" target="_blank" rel="noopener noreferrer">{{ p.title || p.url }}</a>
            <span v-if="p.note" class="playlist-note">{{ p.note }}</span>
          </span>
        </div>
        <div class="playlist-actions">
          <button v-if="canFeature" class="btn btn-ghost playlist-feature" :class="{ active: p.featured }" type="button" :title="t('playlist.featured')" :aria-label="t('playlist.featured')" @click="emit('feature', p)">
            <span aria-hidden="true">{{ p.featured ? '★' : '☆' }}</span>
          </button>
          <button v-if="canRemove" class="btn btn-ghost playlist-delete" type="button" @click="emit('remove', p)">{{ t('common.delete') }}</button>
        </div>
      </li>
    </ul>
    <div v-else class="home-empty">{{ t('playlist.empty') }}</div>
  </section>
</template>
