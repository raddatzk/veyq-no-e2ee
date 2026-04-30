<script setup lang="ts">
import Card from 'primevue/card'
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
  return scopeRoot.kind === 'GROUP' ? 'Gruppe' : 'Privat'
}
</script>

<template>
  <Card class="explorer-card explorer-card-scope-root">
    <template #content>
      <button class="explorer-card-main" type="button" @click="$emit('open', scopeRoot)">
        <span :class="['explorer-card-icon', scopeIcon(scopeRoot)]" />
        <span>
          <strong>{{ scopeRoot.displayName }}</strong>
          <small>{{ scopeLabel(scopeRoot) }} · {{ scopeRoot.path }}</small>
        </span>
      </button>
    </template>
  </Card>
</template>
