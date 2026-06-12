<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import Message from 'primevue/message'
import Button from 'primevue/button'
import ProgressSpinner from 'primevue/progressspinner'
import Select from 'primevue/select'
import ManagementList from '../../../shared/components/ManagementList.vue'
import { getErrorMessage } from '../../../shared/utils/errors'
import { authState } from '../../../authState'
import { capabilityDescription, capabilityTitle } from '../capabilities'
import {
  deleteServerNotification,
  disableManagedUser,
  enableManagedUser,
  getManagedUsers,
  getServerNotifications,
  getServerSettings,
  getServerUpdateEvents,
  updateManagedUserRole,
  type ManagedUserSummary,
  type ServerNotification,
  type ServerRole,
  type ServerSettingsResponse,
  type ServerUpdateEvent
} from '../api'

const settings = ref<ServerSettingsResponse | null>(null)
const users = ref<ManagedUserSummary[]>([])
const notifications = ref<ServerNotification[]>([])
const updateEvents = ref<ServerUpdateEvent[]>([])
const { t, te } = useI18n()
const loading = ref(false)
const savingUserId = ref<number | null>(null)
const error = ref('')
const message = ref('')
const commitUrl = computed(() => {
  const currentSettings = settings.value
  if (!currentSettings?.license.revision) return ''

  const sourceUrl = currentSettings.license.sourceUrl.trim().replace(/\/$/, '')
  if (!/^https:\/\/github\.com\/[^/]+\/[^/]+$/.test(sourceUrl)) return ''

  const revision = currentSettings.license.revision.replace(/^sha-/, '')
  if (!revision) return ''

  return `${sourceUrl}/commit/${revision}`
})
const roleOptions = computed(() => [
  { label: t('settings.serverRoles.ServerOwner'), value: 'ServerOwner' },
  { label: t('settings.serverRoles.ServerMember'), value: 'ServerMember' }
])

onMounted(loadSettings)

async function loadSettings() {
  loading.value = true
  error.value = ''
  message.value = ''
  try {
    const [serverSettings, serverNotifications, serverUpdateEvents] = await Promise.all([
      getServerSettings(),
      getServerNotifications(),
      getServerUpdateEvents()
    ])
    settings.value = serverSettings
    notifications.value = serverNotifications
    updateEvents.value = serverUpdateEvents
    users.value = serverSettings.userManagementAvailable ? (await getManagedUsers()).users : []
  } catch (caught) {
    settings.value = null
    users.value = []
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

async function changeUserRole(user: ManagedUserSummary, role: ServerRole) {
  savingUserId.value = user.userId
  error.value = ''
  message.value = ''
  try {
    upsertUser(await updateManagedUserRole(user.userId, role))
    message.value = t('settings.userRoleSaved')
  } catch (caught) {
    error.value = getErrorMessage(caught, t('settings.userRoleSaveFailed'))
  } finally {
    savingUserId.value = null
  }
}

async function toggleUserEnabled(user: ManagedUserSummary) {
  savingUserId.value = user.userId
  error.value = ''
  message.value = ''
  try {
    upsertUser(user.status === 'Disabled'
      ? await enableManagedUser(user.userId)
      : await disableManagedUser(user.userId)
    )
    message.value = user.status === 'Disabled' ? t('settings.userEnabled') : t('settings.userDisabled')
  } catch (caught) {
    error.value = getErrorMessage(caught, t('settings.userStatusSaveFailed'))
  } finally {
    savingUserId.value = null
  }
}

function upsertUser(user: ManagedUserSummary) {
  const index = users.value.findIndex((item) => item.userId === user.userId)
  if (index === -1) {
    users.value = [...users.value, user].sort(userSort)
  } else {
    users.value.splice(index, 1, user)
  }
}

function userSort(left: ManagedUserSummary, right: ManagedUserSummary): number {
  return (left.email || left.displayName).localeCompare(right.email || right.displayName)
}

function canDisableUser(user: ManagedUserSummary): boolean {
  return user.status !== 'Deleted' && user.userId !== authState.user?.userId
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
    <Message v-if="message" severity="success" :closable="false">{{ message }}</Message>
    <div v-if="loading" class="veyq-loading">
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
          <a v-if="commitUrl" :href="commitUrl" target="_blank" rel="noreferrer">{{ t('license.commit') }}</a>
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
        <a class="veyq-support-button" :href="settings.support.url" target="_blank" rel="noreferrer">
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
      <section v-if="settings.userManagementAvailable" class="settings-form">
        <div class="settings-section-header">
          <div>
            <h2>{{ t('settings.serverUsers') }}</h2>
            <p>{{ t('settings.serverUsersCopy') }}</p>
          </div>
        </div>
        <ManagementList>
          <div v-for="user in users" :key="user.userId" class="veyq-list-row">
            <div class="veyq-row-main">
              <strong class="veyq-row-title">{{ user.displayName }}</strong>
              <span class="veyq-row-subtitle">{{ user.email }}</span>
              <div class="veyq-row-meta">
                <span
                  class="veyq-badge"
                  :class="user.status === 'Active' ? 'success' : user.status === 'Disabled' || user.status === 'Deleted' ? 'danger' : 'warning'"
                >
                  {{ t(`settings.userStatus.${user.status}`) }}
                </span>
              </div>
            </div>
            <Select
              class="management-control"
              :model-value="user.serverRole"
              :options="roleOptions"
              option-label="label"
              option-value="value"
              :disabled="user.status === 'Deleted' || savingUserId === user.userId"
              :aria-label="t('common.role')"
              @update:model-value="changeUserRole(user, $event)"
            />
            <div class="management-actions">
              <Button
                v-if="canDisableUser(user)"
                :icon="user.status === 'Disabled' ? 'pi pi-lock-open' : 'pi pi-lock'"
                :severity="user.status === 'Disabled' ? 'secondary' : 'danger'"
                outlined
                size="small"
                :aria-label="user.status === 'Disabled' ? t('settings.enableUser') : t('settings.disableUser')"
                :title="user.status === 'Disabled' ? t('settings.enableUser') : t('settings.disableUser')"
                :loading="savingUserId === user.userId"
                @click="toggleUserEnabled(user)"
              />
            </div>
          </div>
        </ManagementList>
      </section>
    </template>
  </section>
</template>
