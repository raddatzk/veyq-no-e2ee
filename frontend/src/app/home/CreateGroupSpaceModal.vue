<script setup lang="ts">
import { ref } from 'vue'
import { session } from '../session'
import { t } from '../../i18n/i18n'
import { createGroupSpace } from '../../features/spaces/groups/groupSpaces'
import type { SpaceEntry } from '../../features/home/home'

const props = defineProps<{ tenants: SpaceEntry[] }>()
const emit = defineEmits<{ close: []; created: [scopeId: string] }>()

const name = ref('')
const tenantId = ref(props.tenants[0]?.tenantId ?? '')
const busy = ref(false)
const error = ref('')

async function submit() {
  const trimmed = name.value.trim()
  if (!trimmed || !tenantId.value || !session.unlocked) return
  busy.value = true
  error.value = ''
  try {
    const group = await createGroupSpace(session.apiBase, session.unlocked, tenantId.value, trimmed)
    emit('created', group.scopeId)
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="settings-backdrop" @click.self="emit('close')">
    <div class="settings-panel create-modal-panel">
      <div class="settings-head create-modal-head">
        <h2 class="settings-title">{{ t('groups.createTitle') }}</h2>
      </div>
      <form class="veyq-form create-modal-form" @submit.prevent="submit">
        <label v-if="tenants.length > 1" class="settings-field">
          {{ t('groups.inTenant') }}
          <select v-model="tenantId" required>
            <option v-for="ten in tenants" :key="ten.scopeId" :value="ten.tenantId">{{ ten.displayName }}</option>
          </select>
        </label>
        <label class="settings-field">
          {{ t('groups.name') }}
          <input v-model="name" type="text" required autofocus />
        </label>
        <div v-if="error" class="auth-msg auth-msg-error">{{ error }}</div>
        <div class="veyq-dialog-action-row create-modal-actions">
          <button class="btn btn-ghost" type="button" @click="emit('close')">{{ t('common.cancel') }}</button>
          <button class="btn btn-primary" type="submit" :disabled="busy || !name.trim() || !tenantId">{{ t('common.create') }}</button>
        </div>
      </form>
    </div>
  </div>
</template>
