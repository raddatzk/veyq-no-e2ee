<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import type { MemoryPlaylist } from '../api'
import type { MemoryPlaylistProvider } from '../../../api/generated'

const props = defineProps<{
  playlists: MemoryPlaylist[]
}>()

const { t } = useI18n()
const activePlaylistId = ref<string | null>(null)

const orderedPlaylists = computed(() =>
  [...props.playlists].sort((left, right) => Number(right.featured) - Number(left.featured))
)
const activeIndex = computed(() => {
  const byId = orderedPlaylists.value.findIndex((playlist) => playlist.id === activePlaylistId.value)
  return byId >= 0 ? byId : 0
})
const activePlaylist = computed(() => orderedPlaylists.value[activeIndex.value] ?? null)
const canCycle = computed(() => orderedPlaylists.value.length > 1)
const activeProviderLabel = computed(() =>
  activePlaylist.value ? providerLabel(activePlaylist.value.provider) : ''
)
const activeProviderMark = computed(() =>
  activePlaylist.value ? providerMark(activePlaylist.value.provider) : ''
)

watch(
  orderedPlaylists,
  (playlists) => {
    if (playlists.length === 0) {
      activePlaylistId.value = null
      return
    }
    if (!playlists.some((playlist) => playlist.id === activePlaylistId.value)) {
      activePlaylistId.value = playlists[0].id
    }
  },
  { immediate: true }
)

function providerLabel(provider: MemoryPlaylistProvider): string {
  return t(`playlist.provider.${provider}`)
}

function providerMark(provider: MemoryPlaylistProvider): string {
  return {
    SPOTIFY: 'Spotify',
    APPLE_MUSIC: 'Music',
    YOUTUBE: 'YT',
    SOUNDCLOUD: 'SC',
    DEEZER: 'DZ'
  }[provider]
}

function cyclePlaylist(direction: -1 | 1) {
  if (!canCycle.value) return
  const nextIndex = (activeIndex.value + direction + orderedPlaylists.value.length) % orderedPlaylists.value.length
  activePlaylistId.value = orderedPlaylists.value[nextIndex].id
}
</script>

<template>
  <section v-if="activePlaylist" class="memory-header-playlist" :aria-label="t('playlist.headerPlayer')">
    <div class="memory-header-playlist-shell">
      <Button
        v-if="canCycle"
        v-tooltip.bottom="t('playlist.previous')"
        class="memory-header-playlist-nav"
        icon="pi pi-chevron-left"
        :aria-label="t('playlist.previous')"
        severity="secondary"
        text
        size="small"
        @click="cyclePlaylist(-1)"
      />

      <a
        v-tooltip.bottom="t('playlist.listenOn', { provider: activeProviderLabel })"
        :href="activePlaylist.url"
        class="memory-header-playlist-link"
        :aria-label="t('playlist.openProvider')"
        target="_blank"
        rel="noreferrer"
      >
        <span class="memory-header-playlist-provider-mark">{{ activeProviderMark }}</span>
        <span class="memory-header-playlist-label">{{ activePlaylist.title }}</span>
      </a>

      <Button
        v-if="canCycle"
        v-tooltip.bottom="t('playlist.next')"
        class="memory-header-playlist-nav"
        icon="pi pi-chevron-right"
        :aria-label="t('playlist.next')"
        severity="secondary"
        text
        size="small"
        @click="cyclePlaylist(1)"
      />
    </div>
  </section>
</template>
