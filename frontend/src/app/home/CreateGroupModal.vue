<script setup lang="ts">
import { ref } from 'vue'
import { session } from '../session'
import { t } from '../../i18n/i18n'
import { createGroup } from '../../features/spaces/invite/groups'

const emit = defineEmits<{ close: []; created: [tenantId: string] }>()
const name = ref('')
const domain = ref('')
const busy = ref(false)
const error = ref('')

async function submit() {
  const trimmed = name.value.trim()
  const dom = domain.value.trim().toLowerCase()
  if (!trimmed || !dom || !session.unlocked) return
  busy.value = true
  error.value = ''
  try {
    const tenant = await createGroup(session.apiBase, session.unlocked, trimmed, dom)
    emit('created', tenant.id)
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
        <h2 class="settings-title">{{ t('groups.createTitle') }}</h2>
        <button class="btn btn-ghost" type="button" @click="emit('close')">{{ t('common.close') }}</button>
      </div>
      <form class="veyq-form" @submit.prevent="submit">
        <label>
          {{ t('groups.name') }}
          <input v-model="name" type="text" required autofocus />
        </label>
        <label>
          {{ t('tenants.domain') }}
          <input v-model="domain" type="text" required placeholder="acme.veyq.app" autocapitalize="none" autocomplete="off" />
          <span class="field-hint">{{ t('tenants.domainHint') }}</span>
        </label>
        <div v-if="error" class="auth-msg auth-msg-error">{{ error }}</div>
        <button class="btn btn-primary" type="submit" :disabled="busy">{{ t('common.create') }}</button>
      </form>
    </div>
  </div>
</template>
