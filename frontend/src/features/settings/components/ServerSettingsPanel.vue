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
  <section class="veyq-surface settings-panel">
    <h1>{{ t('settings.serverTitle') }}</h1>
    <p>{{ t('settings.serverSettings') }}</p>

    <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>
    <div v-else-if="loading" class="veyq-loading">
      <ProgressSpinner class="small-spinner" />
      <span>{{ t('settings.serverLoading') }}</span>
    </div>
    <template v-else-if="settings">
      <div class="veyq-list-row settings-entry">
        <div>
          <strong>{{ settings.displayName }}</strong>
          <span>{{ settings.userManagementAvailable ? t('common.active') : t('common.inactive') }}</span>
        </div>
        <i class="pi pi-server"></i>
      </div>
      <div class="veyq-list-row settings-entry license-settings-entry">
        <div>
          <strong>{{ t('license.title') }}</strong>
          <span>{{ t('license.summary', { edition: t(`license.edition.${settings.license.edition}`), license: settings.license.license }) }}</span>
          <i>{{ t('license.sourceNotice') }}</i>
        </div>
        <div class="settings-link-list">
          <a :href="settings.license.sourceUrl" target="_blank" rel="noreferrer">{{ t('license.source') }}</a>
          <a :href="settings.license.noticeUrl" target="_blank" rel="noreferrer">NOTICE</a>
          <a :href="settings.license.trademarkPolicyUrl" target="_blank" rel="noreferrer">{{ t('license.trademark') }}</a>
          <a :href="settings.license.commercialBoundaryUrl" target="_blank" rel="noreferrer">{{ t('license.commercialBoundary') }}</a>
        </div>
      </div>
      <div v-if="settings.support" class="veyq-list-row settings-entry support-settings-entry">
        <div>
          <strong>{{ t('support.title') }}</strong>
          <span>{{ t('support.settingsCopy') }}</span>
        </div>
        <a class="p-button p-button-sm" :href="settings.support.url" target="_blank" rel="noreferrer">
          <span class="pi pi-heart" />
          <span>{{ t('support.action') }}</span>
        </a>
      </div>
    </template>
  </section>
</template>
