<script setup lang="ts">
// The shell's top bar (cr-0030, simplified port of legacy WorkspaceTopbar): a CLIENT-SIDE breadcrumb built
// from the local decrypted tree (the blind server computes none). Global search + upload status are later.
import { breadcrumb } from '../../app/breadcrumb'
</script>

<template>
  <header class="app-topbar">
    <nav class="app-crumbs" aria-label="breadcrumb">
      <template v-for="(crumb, index) in breadcrumb.trail" :key="crumb.id ?? 'root'">
        <RouterLink
          class="app-crumb"
          :to="crumb.id ? { name: 'node', params: { nodeId: crumb.id } } : { name: 'home' }"
        >
          {{ crumb.name }}
        </RouterLink>
        <span v-if="index < breadcrumb.trail.length - 1" class="app-crumb-sep" aria-hidden="true">/</span>
      </template>
    </nav>
  </header>
</template>

<style scoped>
.app-topbar {
  display: flex;
  align-items: center;
  height: var(--app-topbar-height);
  padding: 0 1.4rem;
  background: var(--app-overlay);
  border-bottom: 1px solid var(--app-line);
  backdrop-filter: blur(14px);
}
.app-crumbs {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  font-size: 1rem;
}
.app-crumb {
  color: var(--app-muted);
  text-decoration: none;
}
.app-crumb:last-of-type {
  color: var(--app-text);
  font-weight: 700;
}
.app-crumb:not(:last-of-type):hover {
  color: var(--app-accent);
}
.app-crumb-sep {
  color: var(--app-line);
}
</style>
