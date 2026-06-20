<script setup lang="ts">
import { ref } from 'vue'
import { session } from '../session'
import { t } from '../../i18n/i18n'
import { createGroup } from '../../features/spaces/invite/groups'
import { scaleTenantLogo, type ScaledTenantLogo } from '../../features/admin/createTenantInviteOwner/tenant'

const emit = defineEmits<{ close: []; created: [tenantId: string, warning?: string] }>()
const name = ref('')
const domain = ref('')
const logo = ref<ScaledTenantLogo | null>(null)
const busy = ref(false)
const error = ref('')

async function submit() {
  const trimmed = name.value.trim()
  const dom = domain.value.trim().toLowerCase()
  if (!trimmed || !dom || !session.unlocked) return
  busy.value = true
  error.value = ''
  try {
    const tenant = await createGroup(session.apiBase, session.unlocked, trimmed, dom, logo.value ?? undefined)
    emit('created', tenant.id, tenant.logoUploadError ? t('tenants.logoUploadFailed') : undefined)
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    busy.value = false
  }
}

async function chooseLogo(event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0]
  if (!file) return
  try {
    if (logo.value?.previewUrl) URL.revokeObjectURL(logo.value.previewUrl)
    logo.value = await scaleTenantLogo(file)
  } catch (e) {
    error.value = (e as Error).message
  }
}

function clearSelectedLogo() {
  if (logo.value?.previewUrl) URL.revokeObjectURL(logo.value.previewUrl)
  logo.value = null
}
</script>

<template>
  <div class="settings-backdrop" @click.self="emit('close')">
    <div class="settings-panel create-modal-panel">
      <div class="settings-head create-modal-head">
        <h2 class="settings-title">{{ t('tenants.createTitle') }}</h2>
      </div>
      <form class="veyq-form create-modal-form" @submit.prevent="submit">
        <label class="settings-field">
          {{ t('tenants.name') }}
          <input v-model="name" type="text" required autofocus />
        </label>
        <label class="settings-field">
          {{ t('tenants.domain') }}
          <input v-model="domain" type="text" required placeholder="acme.veyq.app" autocapitalize="none" autocomplete="off" />
          <span class="field-hint">{{ t('tenants.domainHint') }}</span>
        </label>
        <div class="settings-field">
          {{ t('tenants.logo') }}
          <div class="tenant-logo-picker">
            <div class="tenant-logo-preview-shell">
              <img v-if="logo" class="tenant-logo-preview" :src="logo.previewUrl" alt="" />
              <span v-else class="tenant-logo-placeholder">+</span>
            </div>
            <div class="tenant-logo-actions">
              <label class="btn btn-ghost tenant-logo-trigger">
                {{ t('tenants.chooseLogo') }}
                <input class="tenant-logo-input" type="file" accept="image/png,image/jpeg,image/webp" @change="chooseLogo" />
              </label>
              <button v-if="logo" class="btn btn-ghost" type="button" @click="clearSelectedLogo">{{ t('common.remove') }}</button>
            </div>
          </div>
        </div>
        <div v-if="error" class="auth-msg auth-msg-error">{{ error }}</div>
        <div class="veyq-dialog-action-row create-modal-actions">
          <button class="btn btn-ghost" type="button" @click="emit('close')">{{ t('common.cancel') }}</button>
          <button class="btn btn-primary" type="submit" :disabled="busy || !name.trim() || !domain.trim()">{{ t('common.create') }}</button>
        </div>
      </form>
    </div>
  </div>
</template>
