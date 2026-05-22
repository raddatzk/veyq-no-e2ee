<script setup lang="ts">
import Card from 'primevue/card'
import { computed } from 'vue'
import { API_BASE } from '../../../shared/api/httpClient'
import type { Memory } from '../api'

const props = defineProps<{
  memory: Memory
}>()

defineEmits<{
  open: [memory: Memory]
}>()

const coverUrl = computed(() => {
  if (!props.memory.coverUrl) return ''
  return `${API_BASE}${props.memory.coverUrl}`
})
</script>

<template>
  <Card class="memory-card">
    <template #content>
      <button class="memory-card-main" type="button" @click="$emit('open', memory)">
        <span class="memory-card-cover">
          <img v-if="coverUrl" :src="coverUrl" :alt="memory.displayName" />
          <span v-else class="memory-card-icon pi pi-images" />
        </span>
        <span class="memory-card-text">
          <strong>{{ memory.displayName }}</strong>
        </span>
      </button>
    </template>
  </Card>
</template>
