<script setup lang="ts">
import { computed, ref } from 'vue'
import { RouterView, useRoute } from 'vue-router'
import Message from 'primevue/message'
import { useAuthState } from '../../shared/state/useAuthState'
import CreateGroupFlowDialog from '../../features/settings/components/CreateGroupFlowDialog.vue'
import CreateTenantDialog from '../tenant/CreateTenantDialog.vue'
import TenantSidebar from './TenantSidebar.vue'
import WorkspaceTopbar from './WorkspaceTopbar.vue'

const createTenantOpen = ref(false)
const createGroupOpen = ref(false)
const shellMessage = ref('')
const navigationRefreshKey = ref(0)
const { authState } = useAuthState()
const route = useRoute()
const publicRouteNames = new Set([
  'setup',
  'login',
  'invite',
  'public-share',
  'password-reset',
  'passkey-reset',
  'email-confirm'
])
const showWorkspaceShell = computed(() =>
  authState.isAuthenticated && !publicRouteNames.has(String(route.name ?? ''))
)
const activeTenantName = computed(() => authState.activeTenantName || authState.tenants[0]?.name || '')

function refreshNavigation() {
  navigationRefreshKey.value += 1
}

function showShellMessage(message: string) {
  shellMessage.value = message
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
        <Message v-if="shellMessage" severity="success" closable @close="shellMessage = ''">
          {{ shellMessage }}
        </Message>
        <RouterView />
      </main>
    </div>
    <CreateTenantDialog v-model:visible="createTenantOpen" @invited="showShellMessage" />
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
