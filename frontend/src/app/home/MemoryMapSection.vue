<script setup lang="ts">
import { t } from '../../i18n/i18n'
import type { Track } from '../../features/memory/track/track'
import { trackStats, formatDistance, formatDuration, formatElevation } from '../../features/memory/track/trackStats'
import MemoryMap from './MemoryMap.vue'

type MapPoint = { id: string; lat: number; lng: number }

defineProps<{
  points: MapPoint[]
  tracks: Track[]
  visibleTracks: Track[]
  hiddenTrackIds: Set<string>
}>()

const emit = defineEmits<{
  selectPin: [id: string]
  toggleTrack: [id: string]
}>()
</script>

<template>
  <section class="memory-map-wrap">
    <slot name="map-toolbar" />
    <MemoryMap v-if="points.length || visibleTracks.length" :points="points" :tracks="visibleTracks" @select="emit('selectPin', $event)" />
    <div v-else class="home-empty">{{ t('map.empty') }}</div>
    <ul v-if="tracks.length" class="track-list">
      <li v-for="track in tracks" :key="track.id" class="track-row">
        <span class="track-meta">
          <label class="track-vis">
            <input type="checkbox" :checked="!hiddenTrackIds.has(track.id)" @change="emit('toggleTrack', track.id)" />
          </label>
          <span class="track-name">🛤 {{ track.name }}</span>
          <span class="track-stat">{{ formatDistance(trackStats(track.points).distanceM) }}</span>
          <span v-if="trackStats(track.points).durationS" class="track-stat">{{ formatDuration(trackStats(track.points).durationS!) }}</span>
          <span v-if="formatElevation(trackStats(track.points))" class="track-stat">{{ formatElevation(trackStats(track.points)) }}</span>
        </span>
        <slot name="track-actions" :track="track" />
      </li>
    </ul>
  </section>
</template>
