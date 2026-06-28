<script setup lang="ts">
// A Memory tile (cr-0030). Template + classes are the design source's MemoryCard verbatim (so memory.css
// styles it identically); only the data is ours — a decrypted TreeNode. No cover/date yet (media display +
// metadata are later slices), so the cover shows the icon placeholder and the subtitle is omitted.
import Card from 'primevue/card'
import type { TreeNode } from '../tree'

defineProps<{ memory: TreeNode }>()
defineEmits<{ open: [memory: TreeNode] }>()
</script>

<template>
  <Card class="veyq-card veyq-card-interactive memory-card">
    <template #content>
      <button class="memory-card-main" type="button" @click="$emit('open', memory)">
        <span class="memory-card-cover">
          <span class="memory-card-icon pi pi-images" />
        </span>
        <span class="memory-card-text">
          <strong>{{ memory.name }}</strong>
        </span>
      </button>
    </template>
  </Card>
</template>
