<script setup lang="ts">
import Card from 'primevue/card'
import type { Memory } from '../api'

defineProps<{
  memory: Memory
}>()

defineEmits<{
  open: [memory: Memory]
}>()
</script>

<template>
  <Card class="memory-card">
    <template #content>
      <button class="memory-card-main" type="button" @click="$emit('open', memory)">
        <span class="memory-card-icon pi pi-images" />
        <span class="memory-card-text">
          <strong>{{ memory.displayName }}</strong>
          <small>{{ memory.mediaCount }} Medien · {{ memory.childCount }} Unter-Memories</small>
        </span>
      </button>
    </template>
  </Card>
</template>
