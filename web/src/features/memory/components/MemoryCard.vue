<script setup lang="ts">
// A Memory tile (cr-0030/0031). Template + classes are the design source's MemoryCard verbatim (so memory.css
// styles it identically); only the data is ours — a decrypted TreeNode. No cover/date yet (media display +
// metadata are later slices). A favorite star sits top-left over the cover (cr-0031); it toggles on click
// without opening the Memory (the card itself is the open button, so the star is a sibling — no nested button).
import { useI18n } from 'vue-i18n'
import Card from 'primevue/card'
import type { TreeNode } from '../tree'

defineProps<{ memory: TreeNode; favorite?: boolean }>()
defineEmits<{ open: [memory: TreeNode]; toggleFavorite: [memory: TreeNode] }>()

const { t } = useI18n()
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
      <button
        class="memory-card-pin"
        :class="{ 'is-favorite': favorite }"
        type="button"
        :aria-pressed="favorite"
        :aria-label="favorite ? t('memory.unfavorite') : t('memory.favorite')"
        @click.stop="$emit('toggleFavorite', memory)"
      >
        <span class="pi" :class="favorite ? 'pi-heart-fill' : 'pi-heart'" />
      </button>
    </template>
  </Card>
</template>

<style scoped>
.memory-card :deep(.p-card-content) {
  position: relative;
}
/* The favorite pin reuses the design's icon-tile language: a soft rounded tile. Inactive = a Surface tile so
   it stays legible over the (accent-tinted) cover; active = the accent-soft tile + filled accent heart, the
   same purple tile the sidebar icons use. */
.memory-card-pin {
  position: absolute;
  top: 0.55rem;
  left: 0.55rem;
  z-index: 2;
  display: grid;
  place-items: center;
  width: 2rem;
  height: 2rem;
  padding: 0;
  border: 1px solid var(--app-line);
  border-radius: 10px;
  background: var(--app-surface);
  color: var(--app-muted);
  cursor: pointer;
  box-shadow: var(--app-shadow-sm);
  transition:
    color 0.15s ease,
    background 0.15s ease,
    border-color 0.15s ease,
    transform 0.1s ease;
}
.memory-card-pin:hover {
  color: var(--app-accent);
  border-color: color-mix(in srgb, var(--app-accent) 28%, var(--app-line));
}
.memory-card-pin:active {
  transform: scale(0.9);
}
.memory-card-pin.is-favorite {
  background: var(--app-accent-soft);
  border-color: color-mix(in srgb, var(--app-accent) 30%, var(--app-line));
  color: var(--app-accent);
}
.memory-card-pin .pi {
  font-size: 0.9rem;
}
</style>
