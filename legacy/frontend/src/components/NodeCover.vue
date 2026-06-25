<script setup lang="ts">
// A node's cover thumbnail (E2EE): loads + decrypts the node's cover media (or its first media) to an object
// URL via loadCover. Shows a fallback icon while loading / when the node has no media. Used in the home list,
// sidebar favourites, chapter cards and the memory-detail header.
import { onBeforeUnmount, ref, watch } from 'vue'
import { loadCover, type ActiveSpace } from '../features/home/home'
import type { MemoryNode } from '../features/memory/createTree/memoryTree'
import { requireSession, session } from '../app/session'

const props = withDefaults(defineProps<{ active: ActiveSpace; node: MemoryNode; icon?: string }>(), {
  icon: 'pi pi-book',
})

const url = ref<string | null>(null)
let current: string | null = null

watch(() => props.node.id, load, { immediate: true })
onBeforeUnmount(revoke)

function revoke() {
  if (current) URL.revokeObjectURL(current)
  current = null
  url.value = null
}

async function load() {
  revoke()
  try {
    const next = await loadCover(session.apiBase, requireSession(), props.active, props.node)
    current = next
    url.value = next
  } catch {
    url.value = null
  }
}
</script>

<template>
  <span class="node-cover">
    <img v-if="url" :src="url" alt="" />
    <i v-else :class="icon" />
  </span>
</template>

<style scoped>
.node-cover {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  background: var(--p-content-border-color, rgba(127, 127, 127, 0.15));
}
.node-cover img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}
.node-cover i {
  opacity: 0.6;
}
</style>
