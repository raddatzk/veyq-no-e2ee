<script setup lang="ts">
// A Memory tile (cr-0030, simplified port of legacy MemoryCard). Icon + decrypted name; a cover thumbnail
// lands with the media-display slice (derivatives are deferred, ADR 0013).
import type { TreeNode } from '../tree'

defineProps<{ memory: TreeNode }>()
defineEmits<{ open: [memory: TreeNode] }>()
</script>

<template>
  <button class="node-card" type="button" @click="$emit('open', memory)">
    <span class="pi pi-images node-card-icon" aria-hidden="true" />
    <strong class="node-card-name">{{ memory.name }}</strong>
  </button>
</template>

<style scoped>
.node-card {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 10px;
  padding: 16px;
  min-height: 96px;
  border: 1px solid var(--app-line);
  border-radius: var(--app-radius-sm);
  background: #fff;
  cursor: pointer;
  text-align: left;
  font: inherit;
  color: var(--app-text);
}
.node-card:hover {
  border-color: var(--app-muted);
  box-shadow: var(--app-shadow-md);
}
.node-card-icon {
  font-size: 1.4rem;
  color: var(--app-muted);
}
.node-card-name {
  font-weight: 600;
  word-break: break-word;
}
</style>
