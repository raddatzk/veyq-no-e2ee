<script setup lang="ts">
import { ref } from 'vue'
import { session } from '../session'
import { t } from '../../i18n/i18n'
import { isNodeNameConflict, renameNode } from '../../features/memory/curate/curate'
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
        <h2 class="settings-title">{{ t('common.rename') }}</h2>
      </div>
      <form class="veyq-form create-modal-form" @submit.prevent="submit">
        <label class="settings-field">{{ t('common.name') }}<input v-model="name" type="text" autofocus /></label>
        <div class="veyq-dialog-action-row create-modal-actions">
          <button class="btn btn-ghost" type="button" @click="emit('close')">{{ t('common.cancel') }}</button>
          <button class="btn btn-primary" type="submit" :disabled="busy || !name.trim()">{{ t('common.rename') }}</button>
        </div>
      </form>
      <div v-if="error" class="auth-msg auth-msg-error">{{ error }}</div>
    </div>
  </div>
</template>
