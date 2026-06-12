<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import DOMPurify from 'dompurify'
import MarkdownIt from 'markdown-it'
import Carousel from 'primevue/carousel'
import MediaLightbox from '../../../components/media/MediaLightbox.vue'
import { mediaContentUrl, mediaImageDisplayUrl, mediaPosterUrl } from '../../../shared/utils/mediaUrls'
import type { Memory, MemoryMedia, MemoryPlaylist, MemoryTrack } from '../api'
import {
  journalEmbedRanges,
  normalizeJournalTrackIds,
  type JournalEmbedRange
} from '../utils/journalMediaMarkdown'
import TrackMap from './TrackMap.vue'

type JournalHtmlBlock = {
  type: 'html'
  html: string
}

type JournalMediaBlock = {
  type: 'media'
  id: string
  caption: string
  media: MemoryMedia | null
}

type JournalCarouselBlock = {
  type: 'carousel'
  caption: string
  items: JournalMediaBlock[]
}

type JournalTrackBlock = {
  type: 'track'
  ids: string[]
  caption: string
  tracks: MemoryTrack[]
  missingIds: string[]
}

type JournalBlock = JournalHtmlBlock | JournalMediaBlock | JournalCarouselBlock | JournalTrackBlock

const props = defineProps<{
  tenantName?: string
  markdown: string
  media: MemoryMedia[]
  tracks?: MemoryTrack[]
  playlists?: MemoryPlaylist[]
  childMemories: Memory[]
}>()

const emit = defineEmits<{
  openMemory: [memory: Memory]
}>()

const { t } = useI18n()
const journalCarouselPages = ref<Record<number, number>>({})
const lightboxItems = ref<MemoryMedia[]>([])
const lightboxMediaId = ref<string | null>(null)
const lightboxCarouselIndex = ref<number | null>(null)
const carouselResponsiveOptions = [
  { breakpoint: '900px', numVisible: 1, numScroll: 1 }
]

const markdownRenderer = new MarkdownIt({
  breaks: true,
  html: false,
  linkify: true
})

const blocks = computed(() => renderJournalBlocks(props.markdown))
const lightboxSelectedIndex = computed(() =>
  lightboxMediaId.value
    ? lightboxItems.value.findIndex((item) => item.id === lightboxMediaId.value)
    : -1
)
const selectedLightboxMedia = computed(() =>
  lightboxSelectedIndex.value >= 0 ? lightboxItems.value[lightboxSelectedIndex.value] : null
)
const canShowPreviousLightbox = computed(() => lightboxSelectedIndex.value > 0)
const canShowNextLightbox = computed(
  () => lightboxSelectedIndex.value >= 0 && lightboxSelectedIndex.value < lightboxItems.value.length - 1
)

function renderJournalBlocks(markdown: string): JournalBlock[] {
  const blocks: JournalBlock[] = []
  let cursor = 0
  for (const embed of journalEmbedRanges(markdown || '')) {
    appendMarkdownBlock(blocks, markdown.slice(cursor, embed.start))
    if (embed.kind === 'media') {
      blocks.push(renderMediaBlock(embed.mediaIds ?? [], embed.label ?? ''))
    } else if (embed.kind === 'track') {
      blocks.push(renderTrackBlock(embed.targetIds ?? [], embed.label ?? ''))
    } else {
      appendHtmlBlock(blocks, renderContextEmbed(embed))
    }
    cursor = embed.end
  }
  appendMarkdownBlock(blocks, markdown.slice(cursor))
  return blocks
}

function appendMarkdownBlock(blocks: JournalBlock[], markdown: string) {
  appendHtmlBlock(blocks, markdownRenderer.render(markdown))
}

function appendHtmlBlock(blocks: JournalBlock[], html: string) {
  const sanitized = DOMPurify.sanitize(html, {
    ADD_ATTR: ['aria-label', 'controls', 'playsinline', 'rel', 'target', 'title']
  })
  if (sanitized.trim()) blocks.push({ type: 'html', html: sanitized })
}

function renderMediaBlock(ids: string[], caption: string): JournalMediaBlock | JournalCarouselBlock {
  const items = ids.map((id) => ({
    type: 'media' as const,
    id,
    caption,
    media: props.media.find((item) => item.id === id) ?? null
  }))
  if (items.length <= 1) return items[0] ?? { type: 'media', id: ids.join(','), caption, media: null }
  return { type: 'carousel', caption, items }
}

function renderTrackBlock(trackIds: string[], caption: string): JournalTrackBlock {
  const uniqueTrackIds = normalizeJournalTrackIds(trackIds)
  const tracks = uniqueTrackIds
    .map((trackId) => props.tracks?.find((item) => item.id === trackId) ?? null)
    .filter((track): track is MemoryTrack => Boolean(track))
  return {
    type: 'track',
    ids: uniqueTrackIds,
    caption,
    tracks,
    missingIds: uniqueTrackIds.filter((trackId) => !tracks.some((track) => track.id === trackId))
  }
}

function mediaSource(media: MemoryMedia): string {
  return mediaImageDisplayUrl(media)
}

function mediaContentSource(media: MemoryMedia): string {
  return mediaContentUrl(media)
}

function mediaPosterSource(media: MemoryMedia): string | undefined {
  return mediaPosterUrl(media) || undefined
}

function mediaAlt(item: JournalMediaBlock): string {
  return stripHtml(item.caption || item.media?.title || item.id)
}

function isVideoMedia(media: MemoryMedia): boolean {
  return media.mimeType.startsWith('video/')
}

function mediaCaption(item: JournalMediaBlock): string {
  return item.caption || item.media?.title || item.id
}

function trackCaption(block: JournalTrackBlock): string {
  if (block.caption) return block.caption
  if (block.tracks.length === 1) return block.tracks[0].title
  return t('memory.journalTrackSelectionTitle', { count: block.tracks.length })
}

function trackStats(block: JournalTrackBlock) {
  const distanceMeters = block.tracks.reduce((sum, track) => sum + track.distanceMeters, 0)
  const ascentMeters = sumOptional(block.tracks.map((track) => track.ascentMeters))
  const descentMeters = sumOptional(block.tracks.map((track) => track.descentMeters))
  const durationMinutes = sumDurationMinutes(block.tracks)
  return [
    { label: t('track.distance'), value: formatDistance(distanceMeters) },
    { label: t('track.duration'), value: durationMinutes === null ? t('common.notAvailable') : formatDuration(durationMinutes) },
    { label: t('track.ascent'), value: ascentMeters === null ? t('common.notAvailable') : formatDistance(ascentMeters) },
    { label: t('track.descent'), value: descentMeters === null ? t('common.notAvailable') : formatDistance(descentMeters) }
  ]
}

function sumOptional(values: Array<number | undefined>): number | null {
  const present = values.filter((value): value is number => value !== undefined)
  if (present.length === 0) return null
  return present.reduce((sum, value) => sum + value, 0)
}

function sumDurationMinutes(tracks: MemoryTrack[]): number | null {
  const durations = tracks
    .map((track) => trackDurationMinutes(track))
    .filter((value): value is number => value !== null)
  if (durations.length === 0) return null
  return durations.reduce((sum, value) => sum + value, 0)
}

function trackDurationMinutes(track: MemoryTrack): number | null {
  if (!track.startedAt || !track.endedAt) return null
  const started = Date.parse(track.startedAt)
  const ended = Date.parse(track.endedAt)
  if (!Number.isFinite(started) || !Number.isFinite(ended) || ended <= started) return null
  return Math.round((ended - started) / 60_000)
}

function formatDuration(totalMinutes: number): string {
  const hours = Math.floor(totalMinutes / 60)
  const minutes = totalMinutes % 60
  if (hours <= 0) return t('track.durationMinutes', { minutes })
  if (minutes === 0) return t('track.durationHours', { hours })
  return t('track.durationHoursMinutes', { hours, minutes })
}

function trackTitles(block: JournalTrackBlock): string {
  return block.tracks.map((track) => track.title).join(' · ')
}

function journalCarouselPage(index: number): number {
  return journalCarouselPages.value[index] ?? 0
}

function updateJournalCarouselPage(index: number, page: number) {
  journalCarouselPages.value = { ...journalCarouselPages.value, [index]: page }
}

function openMediaLightbox(items: MemoryMedia[], mediaId: string, carouselIndex: number | null = null) {
  lightboxItems.value = items
  lightboxMediaId.value = mediaId
  lightboxCarouselIndex.value = carouselIndex
  syncLightboxCarousel()
}

function openSingleMediaLightbox(media: MemoryMedia) {
  openMediaLightbox([media], media.id)
}

function openCarouselLightbox(block: JournalCarouselBlock, blockIndex: number) {
  const items = block.items.map((item) => item.media).filter((media): media is MemoryMedia => Boolean(media))
  if (items.length === 0) return
  const page = Math.min(journalCarouselPage(blockIndex), block.items.length - 1)
  const currentMedia = block.items[page]?.media ?? items[0]
  openMediaLightbox(items, currentMedia.id, blockIndex)
}

function openTrackMediaLightbox(items: MemoryMedia[]) {
  if (items.length === 0) return
  openMediaLightbox(items, items[0].id)
}

function closeMediaLightbox() {
  lightboxMediaId.value = null
  lightboxItems.value = []
  lightboxCarouselIndex.value = null
}

function showPreviousLightbox() {
  if (!canShowPreviousLightbox.value) return
  lightboxMediaId.value = lightboxItems.value[lightboxSelectedIndex.value - 1]?.id || null
  syncLightboxCarousel()
}

function showNextLightbox() {
  if (!canShowNextLightbox.value) return
  lightboxMediaId.value = lightboxItems.value[lightboxSelectedIndex.value + 1]?.id || null
  syncLightboxCarousel()
}

function syncLightboxCarousel() {
  if (lightboxCarouselIndex.value === null || lightboxSelectedIndex.value < 0) return
  updateJournalCarouselPage(lightboxCarouselIndex.value, lightboxSelectedIndex.value)
}

function renderContextEmbed(embed: JournalEmbedRange): string {
  const label = embed.label ?? ''
  if (!embed.targetId) return unavailableEmbed(label)
  if (embed.kind === 'chapter') return renderMemoryEmbed(embed.targetId, label)
  if (embed.kind === 'playlist') return renderPlaylistEmbed(embed.targetId, label)
  return unavailableEmbed(label)
}

function renderMemoryEmbed(memoryId: string, label: string): string {
  const memory = props.childMemories.find((item) => item.id === memoryId)
  if (!memory) return unavailableEmbed(label || memoryId)
  return `<a class="memory-journal-link" href="#" data-memory-id="${escapeAttribute(memory.id)}"><span class="pi pi-sitemap"></span><span>${escapeHtml(label || memory.displayName)}</span></a>`
}

function renderPlaylistEmbed(playlistId: string, label: string): string {
  const playlist = props.playlists?.find((item) => item.id === playlistId)
  if (!playlist) return unavailableEmbed(label || playlistId)
  const provider = providerLabel(playlist.provider)
  return [
    `<a class="memory-journal-context memory-journal-playlist-link"`,
    ` href="${escapeAttribute(playlist.url)}"`,
    ` target="_blank" rel="noreferrer"`,
    ` aria-label="${escapeAttribute(t('playlist.openProvider'))}"`,
    ` title="${escapeAttribute(t('playlist.listenOn', { provider }))}">`,
    `<span class="pi pi-volume-up"></span>`,
    `<span>${escapeHtml(label || playlist.title)}</span>`,
    `<small>${escapeHtml(provider)}</small>`,
    '</a>'
  ].join('')
}

function providerLabel(provider: string): string {
  return t(`playlist.provider.${provider}`)
}

function formatDistance(meters: number): string {
  if (meters >= 1000) return `${(meters / 1000).toLocaleString(undefined, { maximumFractionDigits: 1 })} km`
  return `${meters.toLocaleString()} m`
}

function unavailableEmbed(label: string): string {
  return `<span class="memory-journal-unavailable"><span class="pi pi-ban"></span><span>${escapeHtml(label || t('memory.journalEmbedUnavailable'))}</span></span>`
}

function stripHtml(value: string): string {
  return value.replace(/<[^>]+>/g, '')
}

function escapeHtml(value: string): string {
  return value
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#39;')
}

function escapeAttribute(value: string): string {
  return escapeHtml(value)
}

function handleClick(event: MouseEvent) {
  const target = event.target as HTMLElement
  const link = target.closest<HTMLAnchorElement>('[data-memory-id]')
  if (!link) return
  event.preventDefault()
  const memory = props.childMemories.find((item) => item.id === link.dataset.memoryId)
  if (memory) emit('openMemory', memory)
}
</script>

<template>
  <!-- eslint-disable vue/no-v-html -- Journal HTML is produced by MarkdownIt and sanitized with DOMPurify before rendering. -->
  <div class="memory-journal-rendered" @click="handleClick">
    <template v-for="(block, index) in blocks" :key="index">
      <div v-if="block.type === 'html'" v-html="block.html" />
      <figure v-else-if="block.type === 'media'" class="memory-journal-media">
        <template v-if="block.media">
          <video
            v-if="isVideoMedia(block.media)"
            :src="mediaContentSource(block.media)"
            :poster="mediaPosterSource(block.media)"
            controls
            playsinline
          />
          <button
            v-else
            type="button"
            class="memory-journal-media-lightbox-trigger"
            :aria-label="mediaCaption(block)"
            @click="openSingleMediaLightbox(block.media)"
          >
            <img :src="mediaSource(block.media)" :alt="mediaAlt(block)" />
          </button>
          <figcaption>{{ mediaCaption(block) }}</figcaption>
        </template>
        <span v-else class="memory-journal-unavailable">
          <span class="pi pi-ban" />
          <span>{{ block.caption || block.id || t('memory.journalEmbedUnavailable') }}</span>
        </span>
      </figure>
      <figure
        v-else-if="block.type === 'carousel'"
        class="memory-journal-carousel"
        :aria-label="block.caption || t('memory.journalHelpCarousel')"
      >
        <div class="memory-journal-carousel-frame">
          <Carousel
            :value="block.items"
            :page="journalCarouselPage(index)"
            :num-visible="1"
            :num-scroll="1"
            :responsive-options="carouselResponsiveOptions"
            :show-indicators="block.items.length > 1"
            :show-navigators="block.items.length > 1"
            :prev-button-props="{ 'aria-label': t('media.previous') }"
            :next-button-props="{ 'aria-label': t('media.next') }"
            circular
            @update:page="updateJournalCarouselPage(index, $event)"
          >
            <template #item="{ data }">
              <div class="memory-journal-carousel-item">
                <template v-if="data.media">
                  <video
                    v-if="isVideoMedia(data.media)"
                    :src="mediaContentSource(data.media)"
                    :poster="mediaPosterSource(data.media)"
                    controls
                    playsinline
                  />
                  <button
                    v-else
                    type="button"
                    class="memory-journal-media-lightbox-trigger"
                    :aria-label="mediaCaption(data)"
                    @click="openCarouselLightbox(block, index)"
                  >
                    <img :src="mediaSource(data.media)" :alt="mediaAlt(data)" />
                  </button>
                </template>
                <span v-else class="memory-journal-unavailable">
                  <span class="pi pi-ban" />
                  <span>{{ data.caption || data.id || t('memory.journalEmbedUnavailable') }}</span>
                </span>
              </div>
            </template>
          </Carousel>
        </div>
        <figcaption>{{ block.caption }}</figcaption>
      </figure>
      <figure v-else class="memory-journal-track">
        <template v-if="block.tracks.length">
          <TrackMap :tracks="block.tracks" :media="media" @open-media-group="openTrackMediaLightbox" />
          <figcaption class="memory-journal-track-body">
            <div class="memory-journal-track-heading">
              <span>{{ trackCaption(block) }}</span>
              <small v-if="block.tracks.length > 1">{{ trackTitles(block) }}</small>
            </div>
            <dl class="memory-journal-track-stats">
              <div v-for="stat in trackStats(block)" :key="stat.label">
                <dt>{{ stat.label }}</dt>
                <dd>{{ stat.value }}</dd>
              </div>
            </dl>
            <span v-if="block.missingIds.length" class="memory-journal-track-missing">
              {{ t('memory.journalTrackMissingCount', { count: block.missingIds.length }) }}
            </span>
          </figcaption>
        </template>
        <span v-else class="memory-journal-unavailable">
          <span class="pi pi-ban" />
          <span>{{ block.caption || block.ids.join(',') || t('memory.journalEmbedUnavailable') }}</span>
        </span>
      </figure>
    </template>
    <MediaLightbox
      v-if="selectedLightboxMedia"
      :tenant-name="tenantName || ''"
      :media="selectedLightboxMedia"
      :media-items="lightboxItems"
      :selected-index="lightboxSelectedIndex"
      :can-show-previous="canShowPreviousLightbox"
      :can-show-next="canShowNextLightbox"
      :can-edit-metadata="false"
      :can-delete="false"
      :can-highlight="false"
      @close="closeMediaLightbox"
      @previous="showPreviousLightbox"
      @next="showNextLightbox"
    />
  </div>
</template>
