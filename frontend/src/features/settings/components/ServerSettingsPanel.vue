<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import Message from 'primevue/message'
import Button from 'primevue/button'
import ProgressSpinner from 'primevue/progressspinner'
import { getErrorMessage } from '../../../shared/utils/errors'
import { capabilityDescription, capabilityTitle } from '../capabilities'
import {
  deleteServerNotification,
  getServerNotifications,
  getServerSettings,
  getServerUpdateEvents,
  type ServerNotification,
  type ServerSettingsResponse,
  type ServerUpdateEvent
} from '../api'

const settings = ref<ServerSettingsResponse | null>(null)
const notifications = ref<ServerNotification[]>([])
const updateEvents = ref<ServerUpdateEvent[]>([])
const { t, te } = useI18n()
const loading = ref(false)
const error = ref('')

onMounted(loadSettings)

async function loadSettings() {
  loading.value = true
  error.value = ''
  try {
    const [serverSettings, serverNotifications, serverUpdateEvents] = await Promise.all([
      getServerSettings(),
      getServerNotifications(),
      getServerUpdateEvents()
    ])
    settings.value = serverSettings
    notifications.value = serverNotifications
    updateEvents.value = serverUpdateEvents
  } catch (caught) {
    settings.value = null
    notifications.value = []
    updateEvents.value = []
    error.value = getErrorMessage(caught, t('settings.serverLoadFailed'))
  } finally {
    loading.value = false
  }
}

async function dismissNotification(notificationId: number) {
  await deleteServerNotification(notificationId)
  notifications.value = notifications.value.filter((notification) => notification.id !== notificationId)
}

function formatDate(value: string): string {
  return new Intl.DateTimeFormat(undefined, {
    dateStyle: 'medium',
    timeStyle: 'short'
  }).format(new Date(value))
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
      <Message
        v-for="notification in notifications"
        :key="notification.id"
        severity="error"
        :closable="false"
        class="server-notification"
      >
        <div class="server-notification-content">
          <div>
            <strong>{{ t('settings.autoUpdateFailed') }}</strong>
            <span>
              {{
                t('settings.autoUpdateFailedCopy', {
                  stage: notification.updateEvent?.stage || t('settings.unknown'),
                  image: notification.updateEvent?.image || t('settings.unknown')
                })
              }}
            </span>
          </div>
          <Button
            icon="pi pi-times"
            text
            rounded
            :aria-label="t('settings.dismiss')"
            @click="dismissNotification(notification.id)"
          />
        </div>
      </Message>
      <div class="veyq-list-row settings-entry">
        <div>
          <strong>{{ settings.displayName }}</strong>
          <span>
            {{ settings.userManagementAvailable ? t('common.active') : t('common.inactive') }}
            <template v-if="settings.version"> · {{ settings.version }}</template>
            <template v-if="settings.revision"> · {{ settings.revision }}</template>
          </span>
        </div>
        <i class="pi pi-server"></i>
      </div>
      <div v-if="settings.capabilities.length" class="veyq-list-row settings-entry capability-settings-entry">
        <div>
          <strong>{{ t('settings.capabilities') }}</strong>
          <span>{{ t('settings.capabilitiesCopy') }}</span>
        </div>
        <div class="settings-link-list">
          <span v-for="capability in settings.capabilities" :key="capability.id">
            <strong>{{ capabilityTitle(capability, t, te) }}</strong>
            <span>{{ capabilityDescription(capability, t, te) }}</span>
          </span>
        </div>
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
      <div class="veyq-list-row settings-entry update-log-settings-entry">
        <div>
          <strong>{{ t('settings.updateLog') }}</strong>
          <span>{{ updateEvents.length ? t('settings.updateLogCopy') : t('settings.updateLogEmpty') }}</span>
        </div>
        <div v-if="updateEvents.length" class="settings-link-list">
          <span v-for="event in updateEvents.slice(0, 10)" :key="event.id">
            <strong>{{ t(`settings.updateStatus.${event.status}`) }}</strong>
            <span>
              {{ formatDate(event.occurredAt) }} · {{ event.toVersion || event.fromVersion || event.image }}
              <template v-if="event.stage"> · {{ event.stage }}</template>
            </span>
          </span>
        </div>
      </div>
    </template>
  </section>
</template>
