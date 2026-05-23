<script setup lang="ts">
import Card from 'primevue/card'
import { computed } from 'vue'
import { API_BASE } from '../../../shared/api/httpClient'
import type { Memory } from '../api'
import { formatMemoryDateRange } from '../utils/memoryDateRange'
import MemoryCoverImage from './MemoryCoverImage.vue'

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
const dateSubtitle = computed(() => formatMemoryDateRange(props.memory.startDate, props.memory.endDate))
</script>

<template>
  <Card class="memory-card">
    <template #content>
      <button class="memory-card-main" type="button" @click="$emit('open', memory)">
        <span class="memory-card-cover">
          <MemoryCoverImage
            v-if="coverUrl"
            :src="coverUrl"
            :alt="memory.displayName"
            :crop="memory.coverCrop"
          />
          <span v-else class="memory-card-icon pi pi-images" />
        </span>
        <span class="memory-card-text">
          <strong>{{ memory.displayName }}</strong>
          <small>{{ dateSubtitle }}</small>
        </span>
      </button>
    </template>
  </Card>
</template>
