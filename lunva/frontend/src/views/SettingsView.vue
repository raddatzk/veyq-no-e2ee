<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import Message from 'primevue/message'
import ProgressSpinner from 'primevue/progressspinner'
import { clearExplorerToolbar, setExplorerToolbar } from '../composables/useExplorerToolbar'
import { getSettings, type SettingsResponse } from '../features/settings/api'
import GroupSettingsPanel from '../features/settings/components/GroupSettingsPanel.vue'
import ServerSettingsPanel from '../features/settings/components/ServerSettingsPanel.vue'
import TenantSettingsPanel from '../features/settings/components/TenantSettingsPanel.vue'
import UserSettingsForm from '../features/settings/components/UserSettingsForm.vue'
import { useAuthState } from '../shared/state/useAuthState'

const route = useRoute()
const router = useRouter()
const { authState } = useAuthState()

const settingsPath = computed(() => route.path || '/')
const tenantName = computed(() => settingsPath.value.trim().split('/').filter(Boolean)[0] || '')
const activeTenant = computed(() => authState.tenants.find((tenant) => tenant.name === tenantName.value))
const canManageTenant = computed(() => activeTenant.value?.role === 'TenantOwner')
const groupName = computed(() => {
  const groupSegment = settingsPath.value.trim().split('/').find((segment) => segment.startsWith('@'))
  return groupSegment ? groupSegment.substring(1) : ''
})
const settings = ref<SettingsResponse | null>(null)
const loading = ref(false)
const error = ref('')

watch(settingsPath, loadSettings, { immediate: true })

onBeforeUnmount(() => {
  clearExplorerToolbar()
})

async function loadSettings() {
  loading.value = true
  error.value = ''
  try {
    const response = await getSettings(settingsPath.value)
    settings.value = response
    setExplorerToolbar(
      response.breadcrumbs.map((breadcrumb) => ({
        label: breadcrumb.label,
        command: () => navigateToPath(breadcrumb.path)
      })),
      []
    )
  } catch (caught) {
    settings.value = null
    error.value = caught instanceof Error ? caught.message : 'Einstellungen konnten nicht geladen werden.'
    clearExplorerToolbar()
  } finally {
    loading.value = false
  }
}

function navigateToPath(path: string) {
  void router.push(path)
}
</script>

<template>
  <div class="settings-page">
    <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>

    <div v-if="loading" class="explorer-loading">
      <ProgressSpinner class="small-spinner" />
      <span>Einstellungen werden geladen...</span>
    </div>

    <template v-else-if="settings">
      <ServerSettingsPanel v-if="settings.subject.type === 'SERVER'" />
      <UserSettingsForm v-else-if="settings.subject.type === 'USER'" />
      <template v-else-if="settings.subject.type === 'TENANT'">
        <TenantSettingsPanel v-if="canManageTenant" :tenant-name="tenantName" />
        <GroupSettingsPanel :tenant-name="tenantName" />
      </template>
      <GroupSettingsPanel
        v-else-if="settings.subject.type === 'GROUP'"
        :tenant-name="tenantName"
        :group-name="groupName"
      />
      <section v-else class="settings-panel">
        <h1>{{ settings.subject.label }}</h1>
        <p>Einstellungen</p>
      </section>
    </template>
  </div>
</template>
