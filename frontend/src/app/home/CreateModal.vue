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
    <div class="settings-panel create-modal-panel">
      <div class="settings-head create-modal-head">
        <h2 class="settings-title">{{ type === 'FOLDER' ? t('home.createFolder') : t('home.createMemory') }}</h2>
      </div>
      <form class="veyq-form create-modal-form" @submit.prevent="create">
        <label class="settings-field">{{ t('common.name') }}<input v-model="name" type="text" autofocus /></label>
        <div class="veyq-dialog-action-row create-modal-actions">
          <button class="btn btn-ghost" type="button" @click="emit('close')">{{ t('common.cancel') }}</button>
          <button class="btn btn-primary" type="submit" :disabled="busy || !name.trim()">{{ t('common.create') }}</button>
        </div>
      </form>
      <div v-if="error" class="auth-msg auth-msg-error">{{ error }}</div>
    </div>
  </div>
</template>
