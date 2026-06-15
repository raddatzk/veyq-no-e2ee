<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { session } from '../session'
import { countMedia, downloadMemoryZip } from '../../features/download/download'
import type { ActiveSpace, MemoryNode } from '../../features/home/home'

const props = defineProps<{ active: ActiveSpace; memory: MemoryNode; name: string }>()
const emit = defineEmits<{ close: [] }>()

const countOwn = ref<number | null>(null)
const countAll = ref<number | null>(null)
const busy = ref(false)

onMounted(async () => {
  countOwn.value = await countMedia(session.apiBase, session.unlocked!, props.active, props.memory, false)
  countAll.value = await countMedia(session.apiBase, session.unlocked!, props.active, props.memory, true)
})

async function download(withChapters: boolean) {
  busy.value = true
  try {
    await downloadMemoryZip(session.apiBase, session.unlocked!, props.active, props.memory, props.name, withChapters)
    emit('close')
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="settings-backdrop" @click.self="emit('close')">
    <div class="settings-panel">
      <div class="settings-head">
        <h2 class="settings-title">Herunterladen</h2>
        <button class="btn btn-ghost" type="button" @click="emit('close')">Schließen</button>
      </div>
      <button class="dl-option" type="button" :disabled="busy" @click="download(false)">
        <span class="dl-icon">⬇️</span>
        <span class="dl-text"><strong>Nur diese Memory</strong><small>{{ countOwn ?? '…' }} Originale aus dieser Memory</small></span>
      </button>
      <button class="dl-option" type="button" :disabled="busy" @click="download(true)">
        <span class="dl-icon">🗂️</span>
        <span class="dl-text"><strong>Mit Kapiteln</strong><small>{{ countAll ?? '…' }} Originale aus dieser Memory und ihren Kapiteln</small></span>
      </button>
      <div v-if="busy" class="home-empty">Pakete werden erstellt…</div>
    </div>
  </div>
</template>
