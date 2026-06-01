<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import Message from 'primevue/message'
import ProgressSpinner from 'primevue/progressspinner'
import { clearWorkspaceToolbar, setWorkspaceToolbar } from '../composables/useWorkspaceToolbar'
import { getSettings, type SettingsResponse } from '../features/settings/api'
import GroupSettingsPanel from '../features/settings/components/GroupSettingsPanel.vue'
import ServerSettingsPanel from '../features/settings/components/ServerSettingsPanel.vue'
import TenantSettingsPanel from '../features/settings/components/TenantSettingsPanel.vue'
import UserSettingsForm from '../features/settings/components/UserSettingsForm.vue'
import { useAuthState } from '../shared/state/useAuthState'

const route = useRoute()
const router = useRouter()
const { locale, t } = useI18n()
const { authState } = useAuthState()

const settingsPath = computed(() => route.path || '/')
const tenantName = computed(() => authState.activeTenantName || authState.tenants[0]?.name || '')
const backendSettingsPath = computed(() => {
  if (route.name === 'server-settings') return '/settings'
  if (route.name === 'user-settings') return '/~me'
  if (!tenantName.value) return '/settings'
  return `/${tenantName.value}${settingsPath.value === '/' ? '/settings' : settingsPath.value}`
})
const activeTenant = computed(() => authState.tenants.find((tenant) => tenant.name === tenantName.value))
const canManageTenant = computed(() => activeTenant.value?.role === 'TenantOwner')
const isCreatingGroup = computed(() => route.query.createGroup === '1')
const groupName = computed(() => {
  const groupSegment = settingsPath.value.trim().split('/').find((segment) => segment.startsWith('@'))
  return groupSegment ? groupSegment.substring(1) : ''
})
const settings = ref<SettingsResponse | null>(null)
const loading = ref(false)
const error = ref('')

watch([backendSettingsPath, locale], loadSettings, { immediate: true })

onBeforeUnmount(() => {
  clearWorkspaceToolbar()
})

async function loadSettings() {
  loading.value = true
  error.value = ''
  try {
    const response = await getSettings(backendSettingsPath.value)
    settings.value = response
    setWorkspaceToolbar(
      response.breadcrumbs.map((breadcrumb) => ({
        label: localizeSettingsLabel(breadcrumb.label),
        command: () => navigateToPath(breadcrumb.path)
      })),
      []
    )
  } catch (caught) {
    settings.value = null
    error.value = caught instanceof Error ? caught.message : t('settings.loadFailed')
    clearWorkspaceToolbar()
  } finally {
    loading.value = false
  }
}

function navigateToPath(path: string) {
  void router.push(frontendSettingsPath(path))
}

function localizeSettingsLabel(label: string): string {
  if (label === 'Einstellungen' || label === 'Settings') return t('settings.title')
  if (label === 'Server') return t('settings.serverTitle')
  return label
}

function frontendSettingsPath(path: string): string {
  if (path === '/settings') return '/server/settings'
  const prefix = `/${tenantName.value}`
  if (path === prefix || path === `${prefix}/`) return '/'
  if (path.startsWith(`${prefix}/`)) return path.slice(prefix.length) || '/'
  return path
}
</script>

<template>
  <div class="veyq-page veyq-page-narrow settings-page">
    <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>

    <div v-if="loading" class="veyq-loading">
      <ProgressSpinner class="small-spinner" />
      <span>{{ t('settings.loading') }}</span>
    </div>

    <template v-else-if="settings">
      <ServerSettingsPanel v-if="settings.subject.type === 'SERVER'" />
      <UserSettingsForm v-else-if="settings.subject.type === 'USER'" />
      <template v-else-if="settings.subject.type === 'TENANT'">
        <TenantSettingsPanel v-if="canManageTenant && !isCreatingGroup" :tenant-name="tenantName" />
        <GroupSettingsPanel :tenant-name="tenantName" />
      </template>
      <GroupSettingsPanel
        v-else-if="settings.subject.type === 'GROUP'"
        :tenant-name="tenantName"
        :group-name="groupName"
      />
      <section v-else class="veyq-surface settings-panel">
        <h1>{{ settings.subject.label }}</h1>
        <p>{{ t('settings.title') }}</p>
      </section>
    </template>
  </div>
</template>
