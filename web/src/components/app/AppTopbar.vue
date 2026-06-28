<script setup lang="ts">
// The shell's top bar (cr-0030). Markup + classes are the design source's WorkspaceTopbar verbatim (the
// .workspace-topbar grid: breadcrumb + a search center + actions) so layout.css styles it identically. Ours
// only differs in the data: the breadcrumb is built CLIENT-SIDE from the local decrypted tree (the blind
// server computes none), and global search is a later slice, so the center renders in its disabled state.
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import Breadcrumb from 'primevue/breadcrumb'
import type { MenuItem } from 'primevue/menuitem'
import { breadcrumb } from '../../app/breadcrumb'

const { t } = useI18n()
const router = useRouter()

const home = computed<MenuItem>(() => ({
  label: breadcrumb.trail[0]?.name,
  command: () => void router.push({ name: 'home' }),
}))
const items = computed<MenuItem[]>(() =>
  breadcrumb.trail.slice(1).map((crumb) => ({
    label: crumb.name,
    command: () => void router.push({ name: 'node', params: { nodeId: crumb.id as string } }),
  })),
)
</script>

<template>
  <header class="workspace-topbar">
    <Breadcrumb :model="items" :home="home" class="app-breadcrumb" />
    <div class="workspace-search">
      <label class="workspace-topbar-center workspace-topbar-center-disabled" :aria-label="t('shell.search')">
        <span class="pi pi-search" />
        <input type="search" disabled :placeholder="t('shell.search')" autocomplete="off" />
        <kbd>⌘K</kbd>
      </label>
    </div>
    <div class="workspace-topbar-actions" />
  </header>
</template>
