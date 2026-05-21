<script setup lang="ts">
import { ref } from 'vue'
import { RouterView } from 'vue-router'
import { useAuthState } from '../../shared/state/useAuthState'
import CreateTenantDialog from '../tenant/CreateTenantDialog.vue'
import TenantSidebar from './TenantSidebar.vue'
import WorkspaceTopbar from './WorkspaceTopbar.vue'

const createTenantOpen = ref(false)
const { authState } = useAuthState()
</script>

<template>
  <div v-if="authState.isAuthenticated" class="app-shell">
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
