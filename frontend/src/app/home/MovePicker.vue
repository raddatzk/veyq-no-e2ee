<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { session } from '../session'
import { t } from '../../i18n/i18n'
import { moveDestinations, type ActiveSpace, type LevelEntry } from '../../features/home/home'
import { isNodeNameConflict, moveNode } from '../../features/memory/curate/curate'

const props = defineProps<{ active: ActiveSpace; entry: LevelEntry }>()
const emit = defineEmits<{ close: []; moved: [] }>()

const dests = ref<{ id: string; name: string }[]>([])
const busy = ref(false)
const error = ref('')

onMounted(async () => {
  dests.value = await moveDestinations(session.apiBase, session.unlocked!, props.active, props.entry.node)
})

async function move(destId: string) {
  busy.value = true
  error.value = ''
  try {
    await moveNode(session.apiBase, session.unlocked!, props.active, props.entry.node, destId)
    emit('moved')
  } catch (e) {
    error.value = isNodeNameConflict(e) ? t('home.nameConflict') : (e as Error).message
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="settings-backdrop" @click.self="emit('close')">
    <div class="settings-panel">
      <div class="settings-head">
        <h2 class="settings-title">{{ t('move.title') }}</h2>
        <button class="btn btn-ghost" type="button" @click="emit('close')">{{ t('common.close') }}</button>
      </div>
      <ul class="search-results">
        <li v-for="d in dests" :key="d.id">
          <button class="search-hit" :disabled="busy" @click="move(d.id)">
            <span class="search-hit-icon">{{ d.id === 'root' ? '🏠' : '📁' }}</span>
            <span class="search-hit-name">{{ d.name }}</span>
          </button>
        </li>
      </ul>
      <div v-if="error" class="auth-msg auth-msg-error">{{ error }}</div>
    </div>
  </div>
</template>
