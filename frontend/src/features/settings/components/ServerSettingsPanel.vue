<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import Message from 'primevue/message'
import ProgressSpinner from 'primevue/progressspinner'
import { getErrorMessage } from '../../../shared/utils/errors'
import { getServerSettings, type ServerSettingsResponse } from '../api'

const settings = ref<ServerSettingsResponse | null>(null)
const { t } = useI18n()
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
    error.value = getErrorMessage(caught, t('settings.serverLoadFailed'))
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <section class="settings-panel">
    <h1>{{ t('settings.serverTitle') }}</h1>
    <p>{{ t('settings.serverSettings') }}</p>

    <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>
    <div v-else-if="loading" class="memory-loading">
      <ProgressSpinner class="small-spinner" />
      <span>{{ t('settings.serverLoading') }}</span>
    </div>
    <template v-else-if="settings">
      <div class="settings-entry">
        <div>
          <strong>{{ settings.displayName }}</strong>
          <span>{{ settings.userManagementAvailable ? t('common.active') : t('common.inactive') }}</span>
        </div>
        <i class="pi pi-server"></i>
      </div>
    </template>
  </section>
</template>
