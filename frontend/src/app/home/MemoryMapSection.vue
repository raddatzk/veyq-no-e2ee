<script setup lang="ts">
import { computed } from 'vue'
import { t } from '../../i18n/i18n'
import type { Track } from '../../features/memory/track/track'
import { trackStats, formatDistance, formatDuration, formatElevation } from '../../features/memory/track/trackStats'
import MemoryMap from './MemoryMap.vue'

type MapPoint = { id: string; lat: number; lng: number }

const props = defineProps<{
  points: MapPoint[]
  tracks: Track[]
  visibleTracks: Track[]
  hiddenTrackIds: Set<string>
}>()

const emit = defineEmits<{
  selectPin: [id: string]
  toggleTrack: [id: string]
}>()

const trackRows = computed(() => props.tracks.map((track) => {
  const stats = trackStats(track.points)
  return {
    track,
    hidden: props.hiddenTrackIds.has(track.id),
    distance: formatDistance(stats.distanceM),
    duration: stats.durationS ? formatDuration(stats.durationS) : '',
    elevation: formatElevation(stats),
  }
}))
</script>

<template>
  <section class="memory-map-wrap">
    <slot name="map-toolbar" />
    <MemoryMap v-if="points.length || visibleTracks.length" :points="points" :tracks="visibleTracks" @select="emit('selectPin', $event)" />
    <div v-else class="home-empty">{{ t('map.empty') }}</div>
    <ul v-if="trackRows.length" class="track-list">
      <li v-for="row in trackRows" :key="row.track.id" class="track-row" :class="{ 'track-row-hidden': row.hidden }">
        <div class="track-main">
          <label class="track-vis">
            <input type="checkbox" :aria-label="row.track.name" :checked="!row.hidden" @change="emit('toggleTrack', row.track.id)" />
            <span class="track-vis-indicator" aria-hidden="true"></span>
          </label>
          <span class="track-icon" aria-hidden="true"></span>
          <span class="track-name">{{ row.track.name }}</span>
        </div>
        <div class="track-stats">
          <span class="track-stat">{{ row.distance }}</span>
          <span v-if="row.duration" class="track-stat">{{ row.duration }}</span>
          <span v-if="row.elevation" class="track-stat">{{ row.elevation }}</span>
        </div>
        <div class="track-actions">
          <slot name="track-actions" :track="row.track" />
        </div>
      </li>
    </ul>
  </section>
</template>
