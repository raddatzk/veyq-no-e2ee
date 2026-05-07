<script setup lang="ts">
import { onMounted, ref } from 'vue'
import Message from 'primevue/message'
import ProgressSpinner from 'primevue/progressspinner'
import { getErrorMessage } from '../../../shared/utils/errors'
import { getServerSettings, type ServerSettingsResponse } from '../api'

const settings = ref<ServerSettingsResponse | null>(null)
const loading = ref(false)
const error = ref('')

onMounted(loadSettings)

async function loadSettings() {
  loading.value = true
  error.value = ''
  try {
    settings.value = await getServerSettings()
  } catch (caught) {
    settings.value = null
    error.value = getErrorMessage(caught, 'Server-Einstellungen konnten nicht geladen werden.')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <section class="settings-panel">
    <h1>Server</h1>
    <p>Server-Einstellungen</p>

    <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>
    <div v-else-if="loading" class="explorer-loading">
      <ProgressSpinner class="small-spinner" />
      <span>Server-Einstellungen werden geladen...</span>
    </div>
    <template v-else-if="settings">
      <div class="settings-entry">
        <div>
          <strong>{{ settings.displayName }}</strong>
          <span>{{ settings.userManagementAvailable ? 'Aktiv' : 'Inaktiv' }}</span>
        </div>
        <i class="pi pi-server"></i>
      </div>
    </template>
  </section>
</template>
