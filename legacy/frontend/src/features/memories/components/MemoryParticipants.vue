<script setup lang="ts">
// A memory's participants: sealed People metadata (name, optional linked user). The server stores an opaque
// payload; names are decrypted locally. Minimal add (anonymous name) / remove UI.
import { ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import Button from 'primevue/button'
import { createParticipant, deleteParticipant, listParticipants, type Participant } from '../../../features/people/participants/participants'
import type { ActiveSpace } from '../../../features/home/home'
import { session } from '../../../app/session'
import { t } from '../../../i18n/i18n'

const props = defineProps<{ active: ActiveSpace; nodeId: string }>()
const router = useRouter()

/** A linked (real-account) participant is clickable through to their profile; anonymous ones are not. */
function openProfile(p: Participant) {
  if (p.linkedUserId) void router.push({ name: 'participant-profile', params: { linkedUserId: p.linkedUserId } })
}

const participants = ref<Participant[]>([])
const error = ref('')
const busy = ref(false)

async function load() {
  error.value = ''
  try {
    participants.value = await listParticipants(session.apiBase, session.unlocked!, props.active, props.nodeId)
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  }
}

async function add() {
  const name = window.prompt(t('people.anonymousNamePrompt'))?.trim()
  if (!name) return
  busy.value = true
  try {
    await createParticipant(session.apiBase, session.unlocked!, props.active, props.nodeId, { displayName: name })
    await load()
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    busy.value = false
  }
}

async function remove(p: Participant) {
  busy.value = true
  try {
    await deleteParticipant(session.apiBase, session.unlocked!, props.active, props.nodeId, p.id)
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
  <div class="memory-participants">
    <p v-if="error" class="participants-error">{{ error }}</p>
    <ul class="participant-list">
      <li v-for="p in participants" :key="p.id" class="participant-row">
        <span class="participant-avatar">{{ p.displayName.charAt(0).toUpperCase() }}</span>
        <button v-if="p.linkedUserId" type="button" class="participant-name participant-link" @click="openProfile(p)">{{ p.displayName }}</button>
        <span v-else class="participant-name">{{ p.displayName }}</span>
        <Button type="button" icon="pi pi-times" severity="secondary" text :disabled="busy" :aria-label="t('people.removeParticipant', { name: p.displayName })" @click="remove(p)" />
      </li>
    </ul>
    <Button type="button" :label="t('people.addAnonymous')" icon="pi pi-user-plus" severity="secondary" outlined :disabled="busy" @click="add" />
  </div>
</template>

<style scoped>
.memory-participants { margin: 0.5rem 0 1rem; }
.participants-error { color: var(--p-red-400, #f87171); }
.participant-list { list-style: none; padding: 0; margin: 0 0 0.75rem; display: flex; flex-direction: column; gap: 0.4rem; }
.participant-row { display: flex; align-items: center; gap: 0.6rem; }
.participant-avatar { display: grid; place-items: center; width: 2rem; height: 2rem; border-radius: 50%; background: var(--p-primary-color, #6366f1); color: var(--p-primary-contrast-color, #fff); font-weight: 600; }
.participant-name { flex: 1; }
.participant-link { background: none; border: none; padding: 0; font: inherit; color: var(--p-primary-color, #6366f1); cursor: pointer; text-align: left; }
.participant-link:hover { text-decoration: underline; }
</style>
