<script setup lang="ts">
// A Memory tile (cr-0030, ported from the design source's MemoryCard): a 16/9 accent-soft cover (icon
// placeholder until thumbnails land — ADR 0013) + a name footer. Styling via the shared .veyq-card classes.
import type { TreeNode } from '../tree'

defineProps<{ memory: TreeNode }>()
defineEmits<{ open: [memory: TreeNode] }>()
</script>

<template>
  <button class="memory-card veyq-card veyq-card-interactive" type="button" @click="$emit('open', memory)">
    <span class="memory-card-cover"><i class="pi pi-images" aria-hidden="true" /></span>
    <span class="memory-card-text"><strong>{{ memory.name }}</strong></span>
  </button>
</template>
