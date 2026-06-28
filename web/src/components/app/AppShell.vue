<script setup lang="ts">
// Top-level layout switch (cr-0030, ported from legacy AppShell): routes flagged `meta.layout === 'app'`
// (the authenticated Memory shell) render inside the sidebar + topbar + content grid; everything else
// (setup / login / tenant-accept / tenants) keeps the centered public card. The router gates the app routes
// on session + unlocked, so this only decides layout.
import { computed } from 'vue'
import { RouterView, useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useServerState } from '../../shared/state/useServerState'
import AppSidebar from './AppSidebar.vue'
import AppTopbar from './AppTopbar.vue'

const route = useRoute()
const { serverState } = useServerState()
const { t } = useI18n()
const appLayout = computed(() => route.meta.layout === 'app')
</script>

<template>
  <div v-if="appLayout" class="app-shell">
    <AppSidebar class="app-shell-sidebar" />
    <AppTopbar class="app-shell-topbar" />
    <main class="app-shell-content"><RouterView /></main>
  </div>
  <main v-else class="veyq-shell">
    <div class="veyq-shell-inner">
      <p v-if="serverState.unreachable" class="veyq-banner" role="alert">{{ t('common.unreachable') }}</p>
      <RouterView />
    </div>
  </main>
</template>

<style scoped>
.app-shell {
  display: grid;
  grid-template-columns: 240px 1fr;
  grid-template-rows: 56px 1fr;
  height: 100vh;
}
.app-shell-sidebar {
  grid-row: 1 / -1;
}
.app-shell-topbar {
  grid-column: 2;
  border-bottom: 1px solid var(--app-line);
}
.app-shell-content {
  grid-column: 2;
  overflow: auto;
  padding: 24px;
}
.veyq-shell-inner {
  width: min(460px, 100%);
}
</style>
