<script setup lang="ts">
import { computed } from 'vue'
import Button from 'primevue/button'
import Breadcrumb from 'primevue/breadcrumb'
import { useBreadcrumbs } from '../../composables/useBreadcrumbs'
import { explorerToolbar } from '../../composables/useExplorerToolbar'

const { activeTenant, breadcrumbHome, breadcrumbItems } = useBreadcrumbs()
const items = computed(() => explorerToolbar.active ? explorerToolbar.breadcrumbs : breadcrumbItems.value)
const actions = computed(() => explorerToolbar.active ? explorerToolbar.actions : [])
</script>

<template>
  <header class="workspace-topbar">
    <Breadcrumb :home="breadcrumbHome" :model="items" class="app-breadcrumb" />
    <div class="workspace-actions">
      <template v-if="explorerToolbar.active">
        <Button
          v-for="action in actions"
          :key="action.key"
          :icon="action.icon"
          :label="action.label"
          :severity="action.severity"
          :outlined="action.outlined"
          :disabled="action.disabled"
          size="small"
          @click="action.command"
        />
      </template>
      <template v-else-if="activeTenant">
        <Button icon="pi pi-upload" label="Upload" size="small" />
        <Button icon="pi pi-pencil" label="Bearbeiten" severity="secondary" outlined size="small" />
      </template>
    </div>
  </header>
</template>
