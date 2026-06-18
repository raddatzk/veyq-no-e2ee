<script setup lang="ts">
import { t } from '../../i18n/i18n'
import type { GalleryTile } from '../../features/gallery/gallery'
import type { Playlist } from '../../features/memory/playlist/playlist'
import type { Track } from '../../features/memory/track/track'
import JournalBody from './JournalBody.vue'

defineProps<{
  nodeId: string
  markdown: string
  editing?: boolean
  tileById: Record<string, GalleryTile>
  tracksById: Record<string, Track>
  chapterById: Record<string, { name: string }>
  playlistById?: Record<string, Playlist>
}>()

defineSlots<{
  'journal-edit'?: (props: { nodeId: string; markdown: string }) => unknown
  'journal-bar'?: (props: { nodeId: string; markdown: string }) => unknown
}>()

const emit = defineEmits<{
  openMedia: [tile: GalleryTile]
  openChapter: [nodeId: string]
}>()
</script>

<template>
  <section class="journal">
    <slot v-if="editing" name="journal-edit" :node-id="nodeId" :markdown="markdown" />
    <template v-else>
      <slot name="journal-bar" :node-id="nodeId" :markdown="markdown" />
      <JournalBody
        v-if="markdown.trim()"
        :markdown="markdown"
        :tile-by-id="tileById"
        :tracks-by-id="tracksById"
        :chapter-by-id="chapterById"
        :playlist-by-id="playlistById"
        @open-media="emit('openMedia', $event)"
        @open-chapter="emit('openChapter', $event)"
      />
      <div v-else class="home-empty">{{ t('journal.empty') }}</div>
    </template>
  </section>
</template>
