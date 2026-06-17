<script setup lang="ts">
// A ::media embed with several photos: a one-at-a-time carousel with prev/next arrows + dot indicators (Legacy
// parity), instead of a flat thumbnail row. A single photo renders as just the image. Tapping opens the lightbox.
import { ref } from 'vue'
import type { GalleryTile } from '../../features/gallery/gallery'

const props = defineProps<{ tiles: GalleryTile[] }>()
const emit = defineEmits<{ open: [tile: GalleryTile] }>()
const idx = ref(0)
const step = (d: number) => { idx.value = (idx.value + d + props.tiles.length) % props.tiles.length }
</script>

<template>
  <div class="jcar" :class="{ single: tiles.length === 1 }">
    <button type="button" class="jcar-img" @click="emit('open', tiles[idx])"><img :src="tiles[idx].url" alt="" /></button>
    <template v-if="tiles.length > 1">
      <button type="button" class="jcar-nav jcar-prev" aria-label="prev" @click="step(-1)">‹</button>
      <button type="button" class="jcar-nav jcar-next" aria-label="next" @click="step(1)">›</button>
      <div class="jcar-dots">
        <button v-for="(tile, i) in tiles" :key="tile.id" type="button" class="jcar-dot" :class="{ on: i === idx }" :aria-label="`photo ${i + 1}`" @click="idx = i" />
      </div>
    </template>
  </div>
</template>

<style scoped>
.jcar { position: relative; display: flex; justify-content: center; }
.jcar-img { padding: 0; border: none; background: none; cursor: pointer; max-width: 100%; }
.jcar-img img { display: block; max-height: 360px; max-width: 100%; object-fit: contain; border-radius: var(--app-radius-sm); }
.jcar-nav {
  position: absolute; top: 50%; transform: translateY(-50%);
  width: 2rem; height: 2rem; border: none; border-radius: 999px;
  background: rgba(0, 0, 0, 0.45); color: #fff; font-size: 1.2rem; line-height: 1; cursor: pointer;
}
.jcar-prev { left: 0.4rem; }
.jcar-next { right: 0.4rem; }
.jcar-dots { position: absolute; bottom: 0.5rem; left: 0; right: 0; display: flex; justify-content: center; gap: 0.35rem; }
.jcar-dot { width: 0.5rem; height: 0.5rem; padding: 0; border: none; border-radius: 999px; background: rgba(255, 255, 255, 0.5); cursor: pointer; }
.jcar-dot.on { background: #fff; }
</style>
