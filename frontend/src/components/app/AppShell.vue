<script setup lang="ts">
import { ref } from 'vue'
import { RouterView } from 'vue-router'
import CreateTenantDialog from '../tenant/CreateTenantDialog.vue'
import TenantSidebar from './TenantSidebar.vue'
import WorkspaceTopbar from './WorkspaceTopbar.vue'

const createTenantOpen = ref(false)
</script>

<template>
  <div class="app-shell">
    <WorkspaceTopbar />
    <div class="app-body">
      <TenantSidebar @create-tenant="createTenantOpen = true" />
      <main class="content">
        <RouterView />
      </main>
    </div>
    <CreateTenantDialog v-model:visible="createTenantOpen" />
  </div>
</template>
