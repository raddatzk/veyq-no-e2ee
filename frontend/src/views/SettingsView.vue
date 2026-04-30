<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import Message from 'primevue/message'
import ProgressSpinner from 'primevue/progressspinner'
import { clearExplorerToolbar, setExplorerToolbar } from '../composables/useExplorerToolbar'
import { getSettings, type SettingsResponse } from '../features/settings/api'
import { explorerPathToTenantRoute, routePathToExplorerPath } from '../shared/utils/paths'

const route = useRoute()
const router = useRouter()

const tenantName = computed(() => String(route.params.tenantName || ''))
const settingsPath = computed(() => routePathToExplorerPath(route.params.pathMatch))
const settings = ref<SettingsResponse | null>(null)
const loading = ref(false)
const error = ref('')

watch([tenantName, settingsPath], loadSettings, { immediate: true })

onBeforeUnmount(() => {
  clearExplorerToolbar()
})

async function loadSettings() {
  if (!tenantName.value) return
  loading.value = true
  error.value = ''
  try {
    const response = await getSettings(tenantName.value, settingsPath.value)
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
  void router.push(explorerPathToTenantRoute(tenantName.value, path))
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
      <section class="settings-panel">
        <h1>{{ settings.subject.label }}</h1>
        <p>Einstellungen</p>
      </section>
    </template>
  </div>
</template>
