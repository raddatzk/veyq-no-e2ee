<script setup lang="ts">
import { computed, ref } from 'vue'
import { RouterView, useRoute } from 'vue-router'
import { useAuthState } from '../../shared/state/useAuthState'
import CreateGroupFlowDialog from '../../features/settings/components/CreateGroupFlowDialog.vue'
import CreateTenantDialog from '../tenant/CreateTenantDialog.vue'
import CapabilityNotice from './CapabilityNotice.vue'
import SupportNotice from './SupportNotice.vue'
import TenantSidebar from './TenantSidebar.vue'
import WorkspaceTopbar from './WorkspaceTopbar.vue'

const createTenantOpen = ref(false)
const createGroupOpen = ref(false)
const navigationRefreshKey = ref(0)
const { authState } = useAuthState()
const route = useRoute()
const publicRouteNames = new Set([
  'setup',
  'login',
  'invite',
  'public-share',
  'password-reset',
  'email-confirm'
])
const showWorkspaceShell = computed(() =>
  authState.isAuthenticated && !publicRouteNames.has(String(route.name ?? ''))
)
const activeTenantName = computed(() => String(route.params.tenantName || authState.tenants[0]?.name || ''))

function refreshNavigation() {
  navigationRefreshKey.value += 1
}
</script>

<template>
  <div v-if="showWorkspaceShell" class="app-shell">
    <div class="app-compact-header">
      <TenantSidebar
        :navigation-refresh-key="navigationRefreshKey"
        @create-tenant="createTenantOpen = true"
        @create-group="createGroupOpen = true"
      />
      <WorkspaceTopbar />
    </div>
    <div class="app-workspace">
      <main class="content">
        <CapabilityNotice />
        <SupportNotice v-if="authState.user?.serverRole === 'ServerOwner'" />
        <RouterView />
      </main>
    </div>
    <CreateTenantDialog v-model:visible="createTenantOpen" />
    <CreateGroupFlowDialog
      v-if="activeTenantName"
      v-model:visible="createGroupOpen"
      :tenant-name="activeTenantName"
      @saved="refreshNavigation"
    />
  </div>
  <main v-else class="public-shell">
    <RouterView />
  </main>
</template>
