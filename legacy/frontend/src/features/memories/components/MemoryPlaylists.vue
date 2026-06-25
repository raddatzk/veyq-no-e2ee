<script setup lang="ts">
// Streaming playlists linked to a memory (operator-blind, ADR 0010). The {provider,url,title,note} payload is
// sealed under the scope content key; only the `featured` flag is server-visible. Provider is detected from
// the URL host client-side. Add / feature / remove via the playlist data layer.
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import {
  listPlaylists, createPlaylist, setPlaylistFeatured, deletePlaylist,
  PROVIDER_LABEL, type Playlist,
} from '../../memory/playlist/playlist'
import { requireSession, session } from '../../../app/session'
import type { ActiveSpace } from '../../../features/home/home'

const props = defineProps<{ active: ActiveSpace; nodeId: string }>()
const emit = defineEmits<{ changed: [] }>()
const { t } = useI18n()

const playlists = ref<Playlist[]>([])
const url = ref('')
const title = ref('')
const note = ref('')
const busy = ref(false)
const error = ref('')

onMounted(load)

async function load() {
  error.value = ''
  try {
    playlists.value = await listPlaylists(session.apiBase, requireSession(), props.active, props.nodeId)
  } catch {
    error.value = t('playlist.loadFailed')
  }
}

async function add() {
  if (!url.value.trim()) return
  busy.value = true
  error.value = ''
  try {
    await createPlaylist(session.apiBase, requireSession(), props.active, props.nodeId, url.value.trim(), title.value.trim(), note.value.trim(), false)
    url.value = ''
    title.value = ''
    note.value = ''
    await load()
    emit('changed')
  } catch {
    error.value = t('playlist.saveFailed')
  } finally {
    busy.value = false
  }
}

async function toggleFeatured(p: Playlist) {
  error.value = ''
  try {
    await setPlaylistFeatured(session.apiBase, requireSession(), props.active, props.nodeId, p.id, !p.featured)
    await load()
    emit('changed')
  } catch {
    error.value = t('playlist.saveFailed')
  }
}

async function remove(p: Playlist) {
  error.value = ''
  try {
    await deletePlaylist(session.apiBase, requireSession(), props.active, props.nodeId, p.id)
    playlists.value = playlists.value.filter((x) => x.id !== p.id)
    emit('changed')
  } catch {
    error.value = t('playlist.deleteFailed')
  }
}
</script>

<template>
  <div class="memory-playlists">
    <p v-if="playlists.length === 0" class="playlist-empty">{{ t('playlist.empty') }}</p>

    <div v-for="p in playlists" :key="p.id" class="playlist-row">
      <i class="pi pi-volume-up playlist-icon" />
      <div class="playlist-main">
        <strong>{{ p.title || PROVIDER_LABEL[p.provider] }}</strong>
        <a :href="p.url" target="_blank" rel="noopener noreferrer">{{ t('playlist.listenOn', { provider: PROVIDER_LABEL[p.provider] }) }}</a>
        <small v-if="p.note">{{ p.note }}</small>
      </div>
      <Button
        type="button"
        :icon="p.featured ? 'pi pi-star-fill' : 'pi pi-star'"
        :title="t('playlist.setFeatured')"
        :aria-label="t('playlist.setFeatured')"
        text
        @click="toggleFeatured(p)"
      />
      <Button type="button" icon="pi pi-trash" severity="danger" text :aria-label="t('playlist.delete')" @click="remove(p)" />
    </div>

    <form class="playlist-add veyq-form" @submit.prevent="add">
      <label>{{ t('playlist.url') }}<InputText v-model="url" type="url" :placeholder="t('playlist.supportedProviders')" required /></label>
      <label>{{ t('playlist.name') }}<InputText v-model="title" :placeholder="t('playlist.nameOptional')" /></label>
      <label>{{ t('playlist.note') }}<InputText v-model="note" /></label>
      <Button type="submit" :label="t('playlist.add')" icon="pi pi-plus" :loading="busy" :disabled="!url.trim()" />
    </form>

    <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>
  </div>
</template>

<style scoped>
.memory-playlists { margin-top: 0.5rem; }
.playlist-empty { opacity: 0.55; font-size: 0.9rem; }
.playlist-row {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  padding: 0.6rem 0;
  border-bottom: 1px solid var(--p-content-border-color, rgba(127, 127, 127, 0.15));
}
.playlist-icon { opacity: 0.6; }
.playlist-main { flex: 1; display: flex; flex-direction: column; }
.playlist-main strong { line-height: 1.3; }
.playlist-main a { font-size: 0.85rem; color: var(--p-primary-color, #5b4df5); }
.playlist-main small { opacity: 0.6; }
.playlist-add { margin-top: 1rem; }
</style>
