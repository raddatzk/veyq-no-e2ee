<script setup lang="ts">
// The memory header playlist chip: shows the FEATURED playlist (server-visible flag, ADR 0010) as a compact
// link with provider mark; cycles through the rest. Mirrors the legacy MemoryHeaderPlaylistPlayer on the new
// (E2EE) Playlist type. The link opens the provider (titles/urls are decrypted client-side).
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import { PROVIDER_LABEL, type Playlist, type PlaylistProvider } from '../../memory/playlist/playlist'

const props = defineProps<{ playlists: Playlist[] }>()
const { t } = useI18n()
const activeId = ref<string | null>(null)

const ordered = computed(() => [...props.playlists].sort((a, b) => Number(b.featured) - Number(a.featured)))
const activeIndex = computed(() => {
  const i = ordered.value.findIndex((p) => p.id === activeId.value)
  return i >= 0 ? i : 0
})
const active = computed(() => ordered.value[activeIndex.value] ?? null)
const canCycle = computed(() => ordered.value.length > 1)

const PROVIDER_MARK: Record<PlaylistProvider, string> = {
  SPOTIFY: 'Spotify', APPLE_MUSIC: 'Music', YOUTUBE: 'YT', SOUNDCLOUD: 'SC', DEEZER: 'DZ', OTHER: 'Link',
}

watch(ordered, (list) => {
  if (list.length === 0) { activeId.value = null; return }
  if (!list.some((p) => p.id === activeId.value)) activeId.value = list[0].id
}, { immediate: true })

function cycle(direction: -1 | 1) {
  if (!canCycle.value) return
  const next = (activeIndex.value + direction + ordered.value.length) % ordered.value.length
  activeId.value = ordered.value[next].id
}
</script>

<template>
  <section v-if="active" class="memory-header-playlist" :aria-label="t('playlist.headerPlayer')">
    <div class="memory-header-playlist-shell">
      <Button
        v-if="canCycle"
        class="memory-header-playlist-nav"
        icon="pi pi-chevron-left"
        :aria-label="t('playlist.previous')"
        severity="secondary"
        text
        @click="cycle(-1)"
      />
      <a
        :href="active.url"
        class="memory-header-playlist-link"
        :title="t('playlist.listenOn', { provider: PROVIDER_LABEL[active.provider] })"
        target="_blank"
        rel="noreferrer"
      >
        <span class="memory-header-playlist-provider-mark">{{ PROVIDER_MARK[active.provider] }}</span>
        <span class="memory-header-playlist-label">{{ active.title || PROVIDER_LABEL[active.provider] }}</span>
      </a>
      <Button
        v-if="canCycle"
        class="memory-header-playlist-nav"
        icon="pi pi-chevron-right"
        :aria-label="t('playlist.next')"
        severity="secondary"
        text
        @click="cycle(1)"
      />
    </div>
  </section>
</template>
