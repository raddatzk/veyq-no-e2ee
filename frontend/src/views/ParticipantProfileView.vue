<script setup lang="ts">
// A participant's profile within the active space: their display name (from the sealed participant payload) and
// every Memory/Chapter they're listed in. There is no separate user-profile endpoint — the server is blind to
// participants — so this scans the scope client-side (listParticipantNodeHits) and matches by linkedUserId.
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import Button from 'primevue/button'
import ProgressSpinner from 'primevue/progressspinner'
import { listParticipantNodeHits, type ParticipantNodeHit } from '../features/people/participants/participants'
import { ancestorChain } from '../features/home/home'
import { buildHomePath } from '../features/home/homeRoute'
import { session } from '../app/session'
import { t } from '../i18n/i18n'

const route = useRoute()
const router = useRouter()
const linkedUserId = String(route.params.linkedUserId ?? '')
const loading = ref(true)
const error = ref('')
const hits = ref<ParticipantNodeHit[]>([])
const displayName = ref('')

onMounted(load)

async function load() {
  const active = session.activeSpace
  if (!active || !linkedUserId) { loading.value = false; return }
  try {
    hits.value = await listParticipantNodeHits(session.apiBase, session.unlocked!, active, linkedUserId)
    displayName.value = hits.value[0]?.participant.displayName ?? ''
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

async function openNode(hit: ParticipantNodeHit) {
  const active = session.activeSpace
  if (!active) return
  const chain = await ancestorChain(session.apiBase, session.unlocked!, active, hit.id)
  await router.push(buildHomePath(active.entry, chain.map((c) => c.slug)))
}

function iconFor(type: ParticipantNodeHit['type']): string {
  return type === 'FOLDER' ? 'pi pi-folder' : type === 'CHAPTER' ? 'pi pi-bookmark' : 'pi pi-images'
}
</script>

<template>
  <div class="participant-profile">
    <Button type="button" icon="pi pi-arrow-left" :label="t('participant.back')" severity="secondary" text @click="router.back()" />
    <header class="profile-head">
      <span class="profile-avatar">{{ (displayName || '?').charAt(0).toUpperCase() }}</span>
      <h2>{{ displayName || t('participant.unknown') }}</h2>
    </header>

    <div v-if="loading" class="veyq-loading"><ProgressSpinner class="small-spinner" /></div>
    <p v-else-if="error" class="profile-error">{{ error }}</p>
    <template v-else>
      <h3 class="profile-section">{{ t('participant.sharedMemories') }}</h3>
      <p v-if="!hits.length" class="empty-hint">{{ t('participant.noMemories') }}</p>
      <ul v-else class="profile-nodes">
        <li v-for="hit in hits" :key="hit.id">
          <button type="button" class="profile-node" @click="openNode(hit)">
            <span :class="iconFor(hit.type)" />
            <span class="profile-node-name">{{ hit.name }}</span>
          </button>
        </li>
      </ul>
    </template>
  </div>
</template>

<style scoped>
.participant-profile { max-width: 720px; margin: 0 auto; padding: 0.5rem 0 1.5rem; }
.profile-head { display: flex; align-items: center; gap: 0.85rem; margin: 0.5rem 0 1rem; }
.profile-avatar { display: grid; place-items: center; width: 3rem; height: 3rem; border-radius: 50%; background: var(--p-primary-color, #6366f1); color: var(--p-primary-contrast-color, #fff); font-weight: 700; font-size: 1.25rem; }
.profile-head h2 { margin: 0; }
.profile-error { color: var(--p-red-400, #f87171); }
.profile-section { font-size: 0.95rem; opacity: 0.7; margin: 1rem 0 0.5rem; }
.empty-hint { opacity: 0.6; }
.profile-nodes { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 0.4rem; }
.profile-node { display: flex; align-items: center; gap: 0.6rem; width: 100%; padding: 0.6rem 0.7rem; border: 1px solid var(--p-content-border-color, rgba(127,127,127,0.2)); border-radius: 8px; background: none; color: inherit; cursor: pointer; text-align: left; }
.profile-node:hover { background: var(--p-content-border-color, rgba(127,127,127,0.12)); }
.profile-node-name { flex: 1; }
</style>
