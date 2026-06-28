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
        <span class="pi" :class="favorite ? 'pi-star-fill' : 'pi-star'" />
      </button>
    </template>
  </Card>
</template>

<style scoped>
.memory-card :deep(.p-card-content) {
  position: relative;
}
.memory-card-pin {
  position: absolute;
  top: 0.5rem;
  left: 0.5rem;
  z-index: 2;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 2rem;
  height: 2rem;
  padding: 0;
  border: 1px solid var(--app-line);
  border-radius: 999px;
  background: color-mix(in srgb, var(--app-surface) 78%, transparent);
  color: var(--app-muted);
  cursor: pointer;
  backdrop-filter: blur(6px);
  transition:
    color 0.15s ease,
    border-color 0.15s ease,
    transform 0.1s ease;
}
.memory-card-pin:hover {
  color: var(--app-text);
}
.memory-card-pin:active {
  transform: scale(0.9);
}
.memory-card-pin.is-favorite {
  color: #f59e0b;
  border-color: color-mix(in srgb, #f59e0b 45%, var(--app-line));
}
.memory-card-pin .pi {
  font-size: 0.95rem;
}
</style>
