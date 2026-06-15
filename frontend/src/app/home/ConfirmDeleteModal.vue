<script setup lang="ts">
// A destructive-action gate: the user must type the resource's exact name to confirm. Used for every
// irreversible delete (folder/memory, media, group, tenant) so a slip can't wipe content.
import { ref } from 'vue'
import { t } from '../../i18n/i18n'

const props = defineProps<{ name: string; warning?: string }>()
const emit = defineEmits<{ close: []; confirm: [] }>()

const typed = ref('')
const busy = ref(false)
const error = ref('')

async function go() {
  if (typed.value !== props.name) return
  busy.value = true
  error.value = ''
  try {
    emit('confirm')
  } catch (e) {
    error.value = (e as Error).message
    busy.value = false
  }
}
</script>

<template>
  <div class="settings-backdrop" @click.self="emit('close')">
    <div class="settings-panel">
      <div class="settings-head">
        <h2 class="settings-title">{{ t('delete.title') }}</h2>
        <button class="btn btn-ghost" type="button" @click="emit('close')">{{ t('common.close') }}</button>
      </div>
      <form class="veyq-form" @submit.prevent="go">
        <p class="auth-sub">{{ warning ?? t('delete.warning') }}</p>
        <label>
          {{ t('delete.confirmPrompt', { name }) }}
          <input v-model="typed" type="text" autocomplete="off" autofocus />
        </label>
        <div v-if="error" class="auth-msg auth-msg-error">{{ error }}</div>
        <button class="btn btn-danger" type="submit" :disabled="busy || typed !== name">{{ t('delete.confirm') }}</button>
      </form>
    </div>
  </div>
</template>
