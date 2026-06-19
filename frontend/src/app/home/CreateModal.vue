<script setup lang="ts">
import { ref } from 'vue'
import { session } from '../session'
import { t } from '../../i18n/i18n'
import { createChild, isNodeNameConflict } from '../../features/memory/curate/curate'
import type { ActiveSpace } from '../../features/home/home'
import type { NodeType } from '../../features/memory/createTree/memoryTree'

const props = defineProps<{ active: ActiveSpace; type: NodeType; parentId?: string; orderIndex: number }>()
const emit = defineEmits<{ close: []; created: [] }>()

const name = ref('')
const busy = ref(false)
const error = ref('')

async function create() {
  if (!name.value.trim()) return
  busy.value = true
  error.value = ''
  try {
    await createChild(session.apiBase, session.unlocked!, props.active, props.type, name.value, props.orderIndex, props.parentId)
    emit('created')
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
        <h2 class="settings-title">{{ type === 'FOLDER' ? t('home.createFolder') : t('home.createMemory') }}</h2>
        <button class="btn btn-ghost" type="button" @click="emit('close')">{{ t('common.cancel') }}</button>
      </div>
      <form class="veyq-form" @submit.prevent="create">
        <label>{{ t('common.name') }}<input v-model="name" type="text" autofocus /></label>
        <button class="btn btn-primary" type="submit" :disabled="busy || !name.trim()">{{ t('common.create') }}</button>
      </form>
      <div v-if="error" class="auth-msg auth-msg-error">{{ error }}</div>
    </div>
  </div>
</template>
