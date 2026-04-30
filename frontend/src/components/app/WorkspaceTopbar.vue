<script setup lang="ts">
import { computed } from 'vue'
import Button from 'primevue/button'
import Breadcrumb from 'primevue/breadcrumb'
import { useBreadcrumbs } from '../../composables/useBreadcrumbs'
import { explorerToolbar } from '../../composables/useExplorerToolbar'

const { breadcrumbHome, breadcrumbItems } = useBreadcrumbs()
const fallbackBreadcrumbs = computed(() => [breadcrumbHome.value, ...breadcrumbItems.value])
const items = computed(() =>
  explorerToolbar.active ? explorerToolbar.breadcrumbs : fallbackBreadcrumbs.value
)
const actions = computed(() => (explorerToolbar.active ? explorerToolbar.actions : []))
</script>

<template>
  <header class="workspace-topbar">
    <Breadcrumb :model="items" class="app-breadcrumb" />
    <div class="workspace-actions">
      <template v-if="explorerToolbar.active">
        <Button
          v-for="action in actions"
          :key="action.key"
          :icon="action.icon"
          :label="action.label"
          :severity="action.severity"
          :outlined="action.outlined"
          size="small"
          @click="action.command"
        />
      </template>
    </div>
  </header>
</template>
