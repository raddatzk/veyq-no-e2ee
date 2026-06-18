<script setup lang="ts">
import type { GalleryTile } from '../../features/gallery/gallery'
import type { LayoutResult } from '../../features/gallery/justifiedLayout'

type GalleryBucket = {
  key: string
  label: string
  layout: LayoutResult
}

defineProps<{
  buckets: GalleryBucket[]
  tileById: Record<string, GalleryTile>
}>()

const emit = defineEmits<{
  openTile: [tile: GalleryTile]
}>()
</script>

<template>
  <section v-for="bucket in buckets" :key="bucket.key" class="gallery-bucket">
    <h2 class="bucket-date">{{ bucket.label }}</h2>
    <div class="gallery" :style="{ height: bucket.layout.height + 'px' }">
      <button
        v-for="tile in bucket.layout.tiles"
        :key="tile.id"
        class="gallery-tile"
        :style="{ left: tile.left + 'px', top: tile.top + 'px', width: tile.renderedWidth + 'px', height: tile.renderedHeight + 'px' }"
        @click="emit('openTile', tileById[tile.id])"
      >
        <img :src="tileById[tile.id].url" alt="" />
        <span v-if="tileById[tile.id].highlighted" class="tile-star">★</span>
      </button>
    </div>
  </section>
</template>
