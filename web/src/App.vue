<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { useServerState } from './shared/state/useServerState'

// Minimal app shell for the setup slice: a centered card surface + an unreachable-instance banner. The
// richer legacy shell (nav, banners, tenant switching) arrives with later UI increments.
const { serverState } = useServerState()
const { t } = useI18n()
</script>

<template>
  <main class="veyq-shell">
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
