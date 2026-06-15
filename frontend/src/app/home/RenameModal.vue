<script setup lang="ts">
import { ref } from 'vue'
import { session } from '../session'
import { t } from '../../i18n/i18n'
import { renameNode } from '../../features/memory/curate/curate'
import type { ActiveSpace, LevelEntry } from '../../features/home/home'

const props = defineProps<{ active: ActiveSpace; entry: LevelEntry }>()
const emit = defineEmits<{ close: []; renamed: [] }>()

const name = ref(props.entry.name)
const busy = ref(false)
const error = ref('')

async function submit() {
  if (!name.value.trim()) return
  busy.value = true
  error.value = ''
  try {
    await renameNode(session.apiBase, session.unlocked!, props.active, props.entry.node, name.value)
    emit('renamed')
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="settings-backdrop" @click.self="emit('close')">
    <div class="settings-panel">
      <div class="settings-head">
        <h2 class="settings-title">{{ t('common.rename') }}</h2>
        <button class="btn btn-ghost" type="button" @click="emit('close')">{{ t('common.cancel') }}</button>
      </div>
      <form class="veyq-form" @submit.prevent="submit">
        <label>{{ t('common.name') }}<input v-model="name" type="text" autofocus /></label>
        <button class="btn btn-primary" type="submit" :disabled="busy || !name.trim()">{{ t('common.rename') }}</button>
      </form>
      <div v-if="error" class="auth-msg auth-msg-error">{{ error }}</div>
    </div>
  </div>
</template>
