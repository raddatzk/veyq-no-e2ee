<script setup lang="ts">
// Top-level layout switch (cr-0030). Markup + classes are the design source's AppShell verbatim (the
// .app-shell grid: .app-compact-header places the sidebar + topbar, .app-workspace > main.content holds the
// route) so layout.css styles it identically. Ours only differs in the gate: routes flagged
// `meta.layout === 'app'` render the workspace shell; everything else keeps the centered .public-shell. The
// router already gates app routes on session + unlocked, so this only decides layout.
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
    <div class="app-compact-header">
      <AppSidebar />
      <AppTopbar />
    </div>
    <div class="app-workspace">
      <main class="content">
        <RouterView />
      </main>
    </div>
  </div>
  <main v-else class="public-shell">
    <div class="veyq-shell-inner">
      <p v-if="serverState.unreachable" class="veyq-banner" role="alert">{{ t('common.unreachable') }}</p>
      <RouterView />
    </div>
  </main>
</template>

<style scoped>
.veyq-shell-inner {
  width: min(460px, 100%);
}
</style>
