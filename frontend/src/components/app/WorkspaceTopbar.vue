<script setup lang="ts">
import Button from 'primevue/button'
import Breadcrumb from 'primevue/breadcrumb'
import { useBreadcrumbs } from '../../composables/useBreadcrumbs'

const { activeTenant, breadcrumbHome, breadcrumbItems } = useBreadcrumbs()
</script>

<template>
  <header class="workspace-topbar">
    <Breadcrumb :home="breadcrumbHome" :model="breadcrumbItems" class="app-breadcrumb" />
    <div class="workspace-actions">
      <Button v-if="activeTenant" icon="pi pi-upload" label="Upload" size="small" />
      <Button v-if="activeTenant" icon="pi pi-pencil" label="Bearbeiten" severity="secondary" outlined size="small" />
    </div>
  </header>
</template>
