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
  padding: 0 24px;
  background: #fff;
}
.app-crumbs {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 0.95rem;
}
.app-crumb {
  color: var(--app-muted);
  text-decoration: none;
}
.app-crumb:last-of-type {
  color: var(--app-text);
  font-weight: 600;
}
.app-crumb:hover {
  text-decoration: underline;
}
.app-crumb-sep {
  color: var(--app-line);
}
</style>
