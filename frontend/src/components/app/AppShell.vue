<script setup lang="ts">
import { computed } from 'vue'
import { RouterView, useRoute } from 'vue-router'
import { isUnlocked } from '../../app/session'
import TenantSidebar from './TenantSidebar.vue'
import WorkspaceTopbar from './WorkspaceTopbar.vue'
import SupportNotice from './SupportNotice.vue'

const route = useRoute()
// Locked / public routes render bare; the workspace chrome (sidebar + topbar) shows once unlocked.
const publicRouteNames = new Set(['setup', 'login', 'invite', 'public-share', 'password-reset', 'confirm-email'])
const showWorkspaceShell = computed(() => isUnlocked() && !publicRouteNames.has(String(route.name ?? '')))
</script>

<template>
  <div v-if="showWorkspaceShell" class="app-shell">
    <div class="app-compact-header">
      <TenantSidebar />
      <WorkspaceTopbar />
    </div>
    <div class="app-workspace">
      <SupportNotice />
      <main class="content">
        <RouterView />
      </main>
    </div>
  </div>
  <main v-else class="public-shell">
    <RouterView />
  </main>
</template>
