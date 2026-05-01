<script setup lang="ts">
import { onMounted, ref } from 'vue'
import Message from 'primevue/message'
import ProgressSpinner from 'primevue/progressspinner'
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
    error.value = caught instanceof Error ? caught.message : 'Server-Einstellungen konnten nicht geladen werden.'
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
    <div v-else-if="settings?.userManagementAvailable" class="settings-entry">
      <div>
        <strong>Userverwaltung</strong>
        <span>Benutzerverwaltung wird hier ergänzt.</span>
      </div>
      <i class="pi pi-users"></i>
    </div>
  </section>
</template>
