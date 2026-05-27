<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import DOMPurify from 'dompurify'
import MarkdownIt from 'markdown-it'
import Carousel from 'primevue/carousel'
import { API_BASE } from '../../../shared/api/httpClient'
import type { Memory, MemoryMedia, MemoryPlaylist, MemoryTrack } from '../api'

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

type JournalBlock = JournalHtmlBlock | JournalMediaBlock | JournalCarouselBlock

const props = defineProps<{
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
const carouselResponsiveOptions = [
  { breakpoint: '900px', numVisible: 1, numScroll: 1 }
]

const markdownRenderer = new MarkdownIt({
  breaks: true,
  html: false,
  linkify: true
})

const blocks = computed(() => renderJournalBlocks(props.markdown))

function renderJournalBlocks(markdown: string): JournalBlock[] {
  const rawHtml = markdownRenderer.render(markdown || '')
  const blocks: JournalBlock[] = []
  const mediaEmbed = /<img src="veyq:media\/([^"]+)" alt="([^"]*)"\s*\/?>/g
  let cursor = 0
  for (const match of rawHtml.matchAll(mediaEmbed)) {
    const index = match.index ?? 0
    appendHtmlBlock(blocks, rawHtml.slice(cursor, index))
    blocks.push(renderMediaBlock(decodeURIComponent(match[1]), match[2]))
    cursor = index + match[0].length
  }
  appendHtmlBlock(blocks, rawHtml.slice(cursor))
  return blocks
}

function appendHtmlBlock(blocks: JournalBlock[], html: string) {
  const sanitized = DOMPurify.sanitize(renderVeyqEmbeds(html), {
    ADD_ATTR: ['controls', 'playsinline']
  })
  if (sanitized.trim()) blocks.push({ type: 'html', html: sanitized })
}

function renderVeyqEmbeds(html: string): string {
  return html
    .replace(/<a href="veyq:([^"]+)">([\s\S]*?)<\/a>/g, (_, target: string, label: string) =>
      renderContextEmbed(`veyq:${target}`, label)
    )
}

function renderMediaBlock(mediaIds: string, caption: string): JournalMediaBlock | JournalCarouselBlock {
  const ids = mediaIds.split(',').map((id) => id.trim()).filter(Boolean)
  const items = ids.map((id) => ({
    type: 'media' as const,
    id,
    caption,
    media: props.media.find((item) => item.id === id) ?? null
  }))
  if (items.length <= 1) return items[0] ?? { type: 'media', id: mediaIds, caption, media: null }
  return { type: 'carousel', caption, items }
}

function mediaSource(media: MemoryMedia): string {
  return `${API_BASE}${media.previewUrl || media.contentUrl}`
}

function mediaContentSource(media: MemoryMedia): string {
  return `${API_BASE}${media.contentUrl}`
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

function journalCarouselPage(index: number): number {
  return journalCarouselPages.value[index] ?? 0
}

function updateJournalCarouselPage(index: number, page: number) {
  journalCarouselPages.value = { ...journalCarouselPages.value, [index]: page }
}

function renderContextEmbed(target: string, label: string): string {
  const parsed = parseVeyqTarget(target)
  if (!parsed) return unavailableEmbed(label)
  if (parsed.kind === 'memory') return renderMemoryEmbed(parsed.value, label)
  if (parsed.kind === 'memory-path') return renderMemoryPathEmbed(parsed.value, label)
  if (parsed.kind === 'playlist') return renderPlaylistEmbed(parsed.value, label, parsed.params.get('url') || '')
  if (parsed.kind === 'map') {
    const coords = [parsed.params.get('lat'), parsed.params.get('lng')].filter(Boolean).join(', ')
    return renderSimpleEmbed('pi pi-map-marker', label, coords)
  }
  if (parsed.kind === 'track') return renderTrackEmbed(parsed.value, label)
  return unavailableEmbed(label)
}

function renderMemoryEmbed(memoryId: string, label: string): string {
  const memory = props.childMemories.find((item) => item.id === memoryId)
  if (!memory) return unavailableEmbed(label || memoryId)
  return `<a class="memory-journal-link" href="#" data-memory-id="${escapeAttribute(memory.id)}"><span class="pi pi-sitemap"></span><span>${label || escapeHtml(memory.displayName)}</span></a>`
}

function renderMemoryPathEmbed(path: string, label: string): string {
  const memory = props.childMemories.find((item) => item.path === path || item.path.endsWith(`/${path.replace(/^\/+/, '')}`))
  if (!memory) return unavailableEmbed(label || path)
  return renderMemoryEmbed(memory.id, label || escapeHtml(memory.displayName))
}

function renderTrackEmbed(trackId: string, label: string): string {
  const track = props.tracks?.find((item) => item.id === trackId)
  if (!track) return unavailableEmbed(label || trackId)
  return renderSimpleEmbed(
    'pi pi-map',
    label || escapeHtml(track.title),
    `${escapeHtml(track.title)} · ${formatDistance(track.distanceMeters)}`
  )
}

function renderPlaylistEmbed(playlistId: string, label: string, url: string): string {
  if (!playlistId) return renderSimpleEmbed('pi pi-volume-up', label, url)
  const playlist = props.playlists?.find((item) => item.id === playlistId)
  if (!playlist) return unavailableEmbed(label || playlistId)
  return renderSimpleEmbed('pi pi-volume-up', label || escapeHtml(playlist.title), providerLabel(playlist.provider))
}

function providerLabel(provider: string): string {
  const labels: Record<string, string> = {
    SPOTIFY: 'Spotify',
    APPLE_MUSIC: 'Apple Music',
    YOUTUBE: 'YouTube',
    SOUNDCLOUD: 'SoundCloud',
    DEEZER: 'Deezer'
  }
  return labels[provider] || provider
}

function formatDistance(meters: number): string {
  if (meters >= 1000) return `${(meters / 1000).toLocaleString(undefined, { maximumFractionDigits: 1 })} km`
  return `${meters.toLocaleString()} m`
}

function renderSimpleEmbed(icon: string, label: string, meta: string): string {
  return `<span class="memory-journal-context"><span class="${escapeAttribute(icon)}"></span><span>${label}</span><small>${escapeHtml(meta)}</small></span>`
}

function unavailableEmbed(label: string): string {
  return `<span class="memory-journal-unavailable"><span class="pi pi-ban"></span><span>${label || t('memory.journalEmbedUnavailable')}</span></span>`
}

function parseVeyqTarget(target: string): { kind: string; value: string; params: URLSearchParams } | null {
  if (!target.startsWith('veyq:')) return null
  const rest = target.slice('veyq:'.length)
  const [path, query = ''] = rest.split('?')
  const kind = path.split('/')[0]
  const value = decodeURIComponent(path.split('/').slice(1).join('/'))
  return { kind, value, params: new URLSearchParams(query) }
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
            controls
            playsinline
          />
          <img v-else :src="mediaSource(block.media)" :alt="mediaAlt(block)" />
          <figcaption>{{ mediaCaption(block) }}</figcaption>
        </template>
        <span v-else class="memory-journal-unavailable">
          <span class="pi pi-ban" />
          <span>{{ block.caption || block.id || t('memory.journalEmbedUnavailable') }}</span>
        </span>
      </figure>
      <figure
        v-else
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
                    controls
                    playsinline
                  />
                  <img v-else :src="mediaSource(data.media)" :alt="mediaAlt(data)" />
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
    </template>
  </div>
</template>
