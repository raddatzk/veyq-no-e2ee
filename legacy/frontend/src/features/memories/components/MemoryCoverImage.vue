<script setup lang="ts">
import { computed } from 'vue'
import type { CoverCrop } from '../api'

const props = defineProps<{
  src: string
  alt: string
  crop?: CoverCrop | null
}>()

const imageStyle = computed(() => {
  const crop = props.crop
  if (!crop) {
    return {
      width: '100%',
      height: '100%',
      left: '0',
      top: '0',
      objectFit: 'cover' as const
    }
  }
  return {
    width: `${100 / crop.width}%`,
    height: `${100 / crop.height}%`,
    left: `${(-crop.x / crop.width) * 100}%`,
    top: `${(-crop.y / crop.height) * 100}%`,
    objectFit: 'fill' as const
  }
})
</script>

<template>
  <span class="memory-cover-image">
    <img :src="src" :alt="alt" :style="imageStyle" />
  </span>
</template>
