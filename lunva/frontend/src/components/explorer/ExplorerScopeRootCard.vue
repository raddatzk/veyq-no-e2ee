<script setup lang="ts">
import type { ExplorerScopeRoot } from '../../api/explorerClient'

defineProps<{
  scopeRoot: ExplorerScopeRoot
}>()

defineEmits<{
  open: [scopeRoot: ExplorerScopeRoot]
}>()

function scopeIcon(scopeRoot: ExplorerScopeRoot): string {
  return scopeRoot.kind === 'GROUP' ? 'pi pi-users' : 'pi pi-user'
}

function scopeLabel(scopeRoot: ExplorerScopeRoot): string {
  return scopeRoot.kind === 'GROUP' ? `@${scopeRoot.displayName}` : `~${scopeRoot.displayName}`
}
</script>

<template>
  <button class="explorer-scope-root-card" type="button" @click="$emit('open', scopeRoot)">
    <span :class="['explorer-scope-root-icon', scopeIcon(scopeRoot)]" />
    <strong>{{ scopeLabel(scopeRoot) }}</strong>
  </button>
</template>
