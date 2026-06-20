<script setup lang="ts">
import AppLinkBanner from './components/app/AppLinkBanner.vue'
import AppShell from './components/app/AppShell.vue'
import { bootState } from './app/boot'
import { t } from './i18n/i18n'
</script>

<template>
  <AppLinkBanner />
  <!-- Boot could not reach the instance: a clear error state instead of a blank/broken shell (uc.platform.app-shell E1). -->
  <main v-if="bootState.unreachable" class="boot-error" role="alert">
    <h1>{{ t('app.unreachableTitle') }}</h1>
    <p>{{ t('app.unreachableSub') }}</p>
  </main>
  <AppShell v-else />
</template>

<style scoped>
.boot-error {
  min-height: 60vh;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 0.5rem;
  text-align: center;
  padding: 2rem;
}
.boot-error p { opacity: 0.75; }
</style>
