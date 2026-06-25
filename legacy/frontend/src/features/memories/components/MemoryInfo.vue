<script setup lang="ts">
// Info tab: a statistics + metadata digest for a memory, derived entirely client-side from already-decrypted
// data (operator-blind — the server never sees takenAt, file sizes, coordinates or camera tags). Replaces the
// old detail's Info list, dropping the permalink/path row (no server path resolution in the E2EE model).
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import Message from 'primevue/message'
import ProgressSpinner from 'primevue/progressspinner'
import { loadGalleryTiles, type GalleryTile } from '../../gallery/gallery'
import { formatDateRange } from '../../gallery/dateBuckets'
import { getTracks, type Track } from '../../memory/track/track'
import { trackStats, formatDistance } from '../../memory/track/trackStats'
import { listBuckets, type Bucket } from '../../memory/buckets/buckets'
import { requireSession, session } from '../../../app/session'
import type { ActiveSpace } from '../../../features/home/home'
import type { MemoryNode } from '../../memory/createTree/memoryTree'

const props = defineProps<{
  active: ActiveSpace
  nodeId: string
  node: MemoryNode | null
  childrenCount: number
  playlistCount: number
}>()
const { locale, t } = useI18n()

const tiles = ref<GalleryTile[]>([])
const tracks = ref<Track[]>([])
const buckets = ref<Bucket[]>([])
const loading = ref(false)
const error = ref('')

onMounted(load)
watch(() => props.nodeId, load)

async function load() {
  loading.value = true
  error.value = ''
  try {
    const sess = requireSession()
    const [t1, t2, t3] = await Promise.all([
      loadGalleryTiles(session.apiBase, sess, props.active, props.nodeId),
      getTracks(session.apiBase, sess, props.active, props.nodeId).catch(() => []),
      listBuckets(session.apiBase, sess, props.active, props.nodeId).catch(() => []),
    ])
    tiles.value.forEach((tile) => URL.revokeObjectURL(tile.url))
    tiles.value = t1
    tracks.value = t2
    buckets.value = t3
  } catch {
    error.value = t('memory.infoPanel.loadFailed')
  } finally {
    loading.value = false
  }
}

const typeLabel = computed(() => {
  switch (props.node?.type) {
    case 'CHAPTER': return t('memory.infoPanel.typeChapter')
    case 'FOLDER': return t('memory.infoPanel.typeFolder')
    default: return t('memory.infoPanel.typeMemory')
  }
})

const createdLabel = computed(() => {
  if (!props.node?.createdAt) return t('memory.infoPanel.none')
  return new Intl.DateTimeFormat(locale.value === 'en' ? 'en-US' : 'de-DE', { dateStyle: 'medium' }).format(new Date(props.node.createdAt))
})

const dateRangeLabel = computed(() =>
  tiles.value.length ? formatDateRange(tiles.value, locale.value) : t('memory.infoPanel.none'),
)

const photoCount = computed(() => tiles.value.filter((tile) => tile.mimeType.startsWith('image/')).length)
const videoCount = computed(() => tiles.value.filter((tile) => tile.mimeType.startsWith('video/')).length)

const storageLabel = computed(() => {
  const bytes = tiles.value.reduce((sum, tile) => sum + (tile.fileSize ?? 0), 0)
  return bytes > 0 ? formatBytes(bytes) : t('memory.infoPanel.none')
})

const contributorCount = computed(() => new Set(buckets.value.map((b) => b.ownerKey)).size)

const trackDistanceLabel = computed(() => {
  const total = tracks.value.reduce((sum, track) => sum + trackStats(track.points).distanceM, 0)
  return total > 0 ? formatDistance(total) : t('memory.infoPanel.none')
})

const withLocationCount = computed(() => tiles.value.filter((tile) => tile.lat != null && tile.lng != null).length)

const cameras = computed(() => {
  const set = new Set<string>()
  for (const tile of tiles.value) {
    const label = [tile.cameraMake, tile.cameraModel].filter(Boolean).join(' ').trim()
    if (label) set.add(label)
  }
  return [...set].sort((a, b) => a.localeCompare(b, locale.value))
})

const coverLabel = computed(() =>
  props.node?.coverMediaId ? t('memory.infoPanel.coverSet') : t('memory.infoPanel.coverNone'),
)

function formatBytes(bytes: number): string {
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let value = bytes
  let unit = 0
  while (value >= 1024 && unit < units.length - 1) { value /= 1024; unit += 1 }
  return `${value.toFixed(value >= 100 || unit === 0 ? 0 : 1)} ${units[unit]}`
}
</script>

<template>
  <div class="memory-info">
    <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>

    <div v-if="loading" class="veyq-loading">
      <ProgressSpinner class="small-spinner" />
      <span>{{ t('common.loading') }}</span>
    </div>

    <dl v-else class="memory-info-list">
      <dt>{{ t('memory.infoPanel.type') }}</dt>
      <dd>{{ typeLabel }}</dd>

      <dt>{{ t('memory.infoPanel.created') }}</dt>
      <dd>{{ createdLabel }}</dd>

      <dt>{{ t('memory.infoPanel.dateRange') }}</dt>
      <dd>{{ dateRangeLabel }}</dd>

      <dt>{{ t('memory.infoPanel.media') }}</dt>
      <dd>
        {{ tiles.length }}
        <small v-if="photoCount || videoCount">
          ({{ t('memory.infoPanel.photos', { count: photoCount }) }}<template v-if="videoCount">, {{ t('memory.infoPanel.videos', { count: videoCount }) }}</template>)
        </small>
      </dd>

      <dt>{{ t('memory.infoPanel.storage') }}</dt>
      <dd>{{ storageLabel }}</dd>

      <template v-if="contributorCount > 0">
        <dt>{{ t('memory.infoPanel.contributors') }}</dt>
        <dd>{{ contributorCount }}</dd>
      </template>

      <template v-if="childrenCount > 0">
        <dt>{{ t('memory.infoPanel.chapters') }}</dt>
        <dd>{{ childrenCount }}</dd>
      </template>

      <template v-if="tracks.length > 0">
        <dt>{{ t('memory.infoPanel.tracks') }}</dt>
        <dd>{{ tracks.length }}</dd>
        <dt>{{ t('memory.infoPanel.trackDistance') }}</dt>
        <dd>{{ trackDistanceLabel }}</dd>
      </template>

      <template v-if="playlistCount > 0">
        <dt>{{ t('memory.infoPanel.playlists') }}</dt>
        <dd>{{ playlistCount }}</dd>
      </template>

      <template v-if="withLocationCount > 0">
        <dt>{{ t('memory.infoPanel.withLocation') }}</dt>
        <dd>{{ t('memory.infoPanel.withLocationValue', { count: withLocationCount, total: tiles.length }) }}</dd>
      </template>

      <template v-if="cameras.length > 0">
        <dt>{{ t('memory.infoPanel.cameras') }}</dt>
        <dd>{{ cameras.join(', ') }}</dd>
      </template>

      <dt>{{ t('memory.infoPanel.cover') }}</dt>
      <dd>{{ coverLabel }}</dd>
    </dl>
  </div>
</template>

<style scoped>
.memory-info { margin-bottom: 1.5rem; }
.memory-info-list {
  display: grid;
  grid-template-columns: minmax(8rem, max-content) 1fr;
  gap: 0.55rem 1.25rem;
  margin: 0;
}
.memory-info-list dt { opacity: 0.6; }
.memory-info-list dd { margin: 0; }
.memory-info-list dd small { opacity: 0.6; }
</style>
