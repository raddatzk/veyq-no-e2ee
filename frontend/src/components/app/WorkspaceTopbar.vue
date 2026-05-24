<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Breadcrumb from 'primevue/breadcrumb'
import { useBreadcrumbs } from '../../composables/useBreadcrumbs'
import { workspaceToolbar } from '../../composables/useWorkspaceToolbar'

const { t } = useI18n()
const { fallbackBreadcrumb } = useBreadcrumbs()
const fallbackBreadcrumbs = computed(() => [fallbackBreadcrumb.value])
const items = computed(() =>
  workspaceToolbar.active ? workspaceToolbar.breadcrumbs : fallbackBreadcrumbs.value
)
const actions = computed(() => (workspaceToolbar.active ? workspaceToolbar.actions : []))
</script>

<template>
  <header class="workspace-topbar">
    <Breadcrumb :model="items" class="app-breadcrumb" />
    <div class="workspace-topbar-center" :aria-label="t('navigation.globalSearch')">
      <span class="pi pi-search" />
      <input type="search" :placeholder="t('navigation.searchPlaceholder')" disabled />
      <kbd>⌘K</kbd>
    </div>
    <div class="workspace-actions">
      <Button
        v-for="action in actions"
        :key="action.key"
        :icon="action.icon"
        :aria-label="action.label"
        :severity="action.severity"
        :outlined="action.outlined"
        size="small"
        @click="action.command"
      />
    </div>
  </header>
</template>
