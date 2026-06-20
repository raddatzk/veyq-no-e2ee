<script setup lang="ts">
// A memory's linked streaming playlists (operator-blind): {provider, url, title} sealed under the content
// key; provider detected client-side from the URL host. Minimal add / open / remove UI.
import { ref, watch } from 'vue'
import Button from 'primevue/button'
import { createPlaylist, deletePlaylist, listPlaylists, PROVIDER_LABEL, type Playlist } from '../../../features/memory/playlist/playlist'
import type { ActiveSpace } from '../../../features/home/home'
import { session } from '../../../app/session'
import { t } from '../../../i18n/i18n'

const props = defineProps<{ active: ActiveSpace; nodeId: string }>()
const playlists = ref<Playlist[]>([])
const error = ref('')
const busy = ref(false)

async function load() {
  error.value = ''
  try {
    playlists.value = await listPlaylists(session.apiBase, session.unlocked!, props.active, props.nodeId)
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  }
}

async function add() {
  const url = window.prompt(t('playlist.urlPrompt'))?.trim()
  if (!url) return
  const title = window.prompt(t('playlist.titlePrompt'))?.trim() ?? ''
  busy.value = true
  try {
    await createPlaylist(session.apiBase, session.unlocked!, props.active, props.nodeId, url, title, '', false)
    await load()
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    busy.value = false
  }
}

async function remove(p: Playlist) {
  if (!window.confirm(t('playlist.deleteConfirm'))) return
  busy.value = true
  try {
    await deletePlaylist(session.apiBase, session.unlocked!, props.active, props.nodeId, p.id)
    await load()
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    busy.value = false
  }
}

watch(() => [props.active.scope.scopeId, props.nodeId], load, { immediate: true })
</script>

<template>
  <div class="memory-playlists">
    <p v-if="error" class="playlists-error">{{ error }}</p>
    <ul v-if="playlists.length" class="playlist-list">
      <li v-for="p in playlists" :key="p.id" class="playlist-row">
        <a :href="p.url" target="_blank" rel="noopener noreferrer" class="playlist-link">
          <span class="pi pi-play-circle" />
          <span class="playlist-text">
            <strong>{{ p.title || PROVIDER_LABEL[p.provider] }}</strong>
            <small>{{ PROVIDER_LABEL[p.provider] }}</small>
          </span>
        </a>
        <Button type="button" icon="pi pi-times" severity="secondary" text :disabled="busy" @click="remove(p)" />
      </li>
    </ul>
    <p v-else class="empty-hint">{{ t('playlist.empty') }}</p>
    <Button type="button" :label="t('playlist.add')" icon="pi pi-plus" severity="secondary" outlined :disabled="busy" @click="add" />
  </div>
</template>

<style scoped>
.memory-playlists { margin: 0.5rem 0 1rem; }
.playlists-error { color: var(--p-red-400, #f87171); }
.playlist-list { list-style: none; padding: 0; margin: 0 0 0.75rem; display: flex; flex-direction: column; gap: 0.4rem; }
.playlist-row { display: flex; align-items: center; gap: 0.5rem; }
.playlist-link { display: flex; align-items: center; gap: 0.6rem; text-decoration: none; color: inherit; flex: 1; }
.playlist-text { display: flex; flex-direction: column; }
.playlist-text small { opacity: 0.6; }
.empty-hint { opacity: 0.6; }
</style>
