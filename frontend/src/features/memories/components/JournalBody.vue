<script setup lang="ts">
// Read-only journal renderer with rich embeds (matches iOS JournalBodyView / Android). The body is split into
// Markdown text segments (rendered by the safe subset renderer) and embed blocks: ::media → thumbnail/carousel
// (click opens the lightbox), ::track → an inline mini-map of the route(s) plus per-track stats, ::chapter → a
// link chip into that chapter, ::playlist → a card linking out to the streaming provider. An id that resolves to
// nothing (deleted media/track/chapter) degrades to a placeholder note rather than vanishing. Resolution data
// (tiles, tracks, chapters, playlists) is pre-loaded by the parent and passed in by id, so this component stays
// presentational and is reusable by both the member detail view and the guest share viewer.
import { computed } from 'vue'
import { t } from '../../../i18n/i18n'
import { renderMarkdown } from '../../../features/memory/journal/markdown'
import { splitJournalBlocks } from '../../../features/memory/journal/journalEmbed'
import type { GalleryTile } from '../../../features/gallery/gallery'
import type { Track } from '../../../features/memory/track/track'
import { trackStats, formatDistance, formatDuration, formatElevation } from '../../../features/memory/track/trackStats'
import { PROVIDER_LABEL, type Playlist } from '../../../features/memory/playlist/playlist'
import JournalMediaCarousel from './JournalMediaCarousel.vue'
import JournalTrackMap from './JournalTrackMap.vue'

const props = defineProps<{
  markdown: string
  tileById: Record<string, GalleryTile>
  tracksById: Record<string, Track>
  chapterById: Record<string, { name: string }>
  playlistById?: Record<string, Playlist>
}>()
const emit = defineEmits<{ openMedia: [tile: GalleryTile]; openChapter: [nodeId: string] }>()

const blocks = computed(() => splitJournalBlocks(props.markdown))

const tilesFor = (ids: string[]): GalleryTile[] => ids.map((id) => props.tileById[id]).filter(Boolean)
const tracksFor = (ids: string[]): Track[] => ids.map((id) => props.tracksById[id]).filter(Boolean)
</script>

<template>
  <div class="journal-body">
    <template v-for="(block, i) in blocks" :key="i">
      <!-- renderMarkdown HTML-escapes input first (safe subset); no raw HTML passes through. -->
      <!-- eslint-disable-next-line vue/no-v-html -->
      <div v-if="block.type === 'markdown' && block.markdown.trim()" v-html="renderMarkdown(block.markdown)"></div>

      <!-- media: a single photo or a swipeable carousel with indicators; click opens the existing lightbox -->
      <figure v-else-if="block.type === 'embed' && block.embed.kind === 'media'" class="journal-embed journal-embed-media">
        <JournalMediaCarousel v-if="tilesFor(block.embed.ids).length" :tiles="tilesFor(block.embed.ids)" @open="emit('openMedia', $event)" />
        <p v-else class="journal-embed-missing">{{ t('journal.embedMissing') }}</p>
        <figcaption v-if="block.embed.label" class="journal-embed-caption">{{ block.embed.label }}</figcaption>
      </figure>

      <!-- track: inline mini-map of the route(s) + per-track distance / duration / ascent stats -->
      <figure v-else-if="block.type === 'embed' && block.embed.kind === 'track'" class="journal-embed journal-embed-track">
        <template v-if="tracksFor(block.embed.ids).length">
          <JournalTrackMap :tracks="tracksFor(block.embed.ids)" />
          <ul class="journal-track-stats">
            <li v-for="tr in tracksFor(block.embed.ids)" :key="tr.id">
              <span class="track-name"><span class="journal-inline-icon pi pi-map" aria-hidden="true"></span>{{ tr.name }}</span>
              <span class="track-stat">{{ formatDistance(trackStats(tr.points).distanceM) }}</span>
              <span v-if="trackStats(tr.points).durationS" class="track-stat">{{ formatDuration(trackStats(tr.points).durationS!) }}</span>
              <span v-if="formatElevation(trackStats(tr.points))" class="track-stat">{{ formatElevation(trackStats(tr.points)) }}</span>
            </li>
          </ul>
        </template>
        <p v-else class="journal-embed-missing">{{ t('journal.embedMissing') }}</p>
        <figcaption v-if="block.embed.label" class="journal-embed-caption">{{ block.embed.label }}</figcaption>
      </figure>

      <!-- chapter: a link into the child chapter -->
      <p v-else-if="block.type === 'embed' && block.embed.kind === 'chapter'" class="journal-embed journal-embed-chapter">
        <a
          v-if="chapterById[block.embed.ids[0]]"
          class="journal-chapter-link"
          role="link"
          tabindex="0"
          @click="emit('openChapter', block.embed.ids[0])"
          @keydown.enter="emit('openChapter', block.embed.ids[0])"
        >
          <span class="journal-chapter-icon pi pi-folder" aria-hidden="true"></span>
          <span>{{ block.embed.label || chapterById[block.embed.ids[0]].name }}</span>
        </a>
        <span v-else class="journal-embed-missing">{{ t('journal.embedMissing') }}</span>
      </p>

      <!-- playlist: a card linking out to the streaming provider -->
      <p v-else-if="block.type === 'embed' && block.embed.kind === 'playlist'" class="journal-embed journal-embed-playlist">
        <a
          v-if="playlistById && playlistById[block.embed.ids[0]]"
          class="journal-playlist-link"
          :href="playlistById[block.embed.ids[0]].url"
          target="_blank"
          rel="noopener noreferrer"
        >
          <span class="journal-playlist-provider">{{ PROVIDER_LABEL[playlistById[block.embed.ids[0]].provider] }}</span>
          <span>{{ block.embed.label || playlistById[block.embed.ids[0]].title || playlistById[block.embed.ids[0]].url }}</span>
        </a>
        <span v-else class="journal-embed-missing">{{ t('journal.embedMissing') }}</span>
      </p>
    </template>
  </div>
</template>

<style scoped>
.journal-body { line-height: 1.6; }
.journal-body :deep(h1), .journal-body :deep(h2), .journal-body :deep(h3) { margin: 1rem 0 0.5rem; }
.journal-body :deep(p) { margin: 0.5rem 0; }
.journal-embed {
  display: grid;
  gap: 0.5rem;
  margin: 1.1rem 0;
}
.journal-embed-media,
.journal-embed-track {
  max-width: 760px;
}
.journal-embed-caption {
  margin-top: 0;
  font-size: 0.9rem;
  color: var(--app-muted, rgba(127, 127, 127, 0.85));
}
.journal-embed-missing { font-size: 0.85rem; color: var(--app-muted, rgba(127, 127, 127, 0.85)); font-style: italic; }

.journal-embed-track {
  overflow: hidden;
  background: var(--app-surface, rgba(127, 127, 127, 0.06));
  border: 1px solid var(--app-line, rgba(127, 127, 127, 0.2));
  border-radius: 12px;
}
.journal-track-stats {
  display: grid;
  gap: 0.4rem;
  list-style: none;
  margin: 0;
  padding: 0 0.85rem 0.85rem;
}
.journal-track-stats li { display: flex; flex-wrap: wrap; gap: 0.6rem; align-items: baseline; font-size: 0.85rem; }
.journal-track-stats .track-name { font-weight: 600; display: inline-flex; align-items: center; gap: 0.4rem; }
.journal-track-stats .track-stat { color: var(--app-muted, rgba(127, 127, 127, 0.85)); }

.journal-chapter-link,
.journal-playlist-link {
  display: inline-flex;
  align-items: center;
  gap: 0.45rem;
  max-width: 100%;
  padding: 0.45rem 0.65rem;
  color: inherit;
  background: var(--app-surface, rgba(127, 127, 127, 0.06));
  border: 1px solid var(--app-line, rgba(127, 127, 127, 0.2));
  border-radius: 8px;
  font-size: 0.95rem;
  cursor: pointer;
  text-decoration: none;
  vertical-align: middle;
}
.journal-chapter-link:hover,
.journal-playlist-link:hover {
  border-color: var(--p-primary-color, #6366f1);
}
.journal-playlist-provider {
  font-size: 0.75rem;
  font-weight: 600;
  color: var(--app-muted, rgba(127, 127, 127, 0.85));
  background: var(--app-surface, rgba(127, 127, 127, 0.06));
  padding: 0.1rem 0.5rem;
  border-radius: 999px;
}
</style>
