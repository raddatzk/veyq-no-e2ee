<script setup lang="ts">
import { computed, ref } from 'vue'
import { RouterView, useRoute } from 'vue-router'
import { useAuthState } from '../../shared/state/useAuthState'
import CreateTenantDialog from '../tenant/CreateTenantDialog.vue'
import TenantSidebar from './TenantSidebar.vue'
import WorkspaceTopbar from './WorkspaceTopbar.vue'

const createTenantOpen = ref(false)
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
</script>

<template>
  <div v-if="showWorkspaceShell" class="app-shell">
    <TenantSidebar @create-tenant="createTenantOpen = true" />
    <div class="app-workspace">
      <WorkspaceTopbar />
      <main class="content">
        <RouterView />
      </main>
    </div>
    <CreateTenantDialog v-model:visible="createTenantOpen" />
  </div>
  <main v-else class="public-shell">
    <RouterView />
  </main>
</template>
