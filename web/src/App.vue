<script setup lang="ts">
// Root: delegates layout to AppShell, which picks the authenticated Memory shell (sidebar + topbar) or the
// centered public card per the active route (cr-0030).
import AppShell from './components/app/AppShell.vue'
</script>

<template>
  <AppShell />
</template>
