<script setup lang="ts">
import VeyqCardButton from '../ui/VeyqCardButton.vue'
import VeyqIconButton from '../ui/VeyqIconButton.vue'

defineOptions({ inheritAttrs: false })

defineProps<{
  name: string
  cover?: string | null
  menuLabel?: string
  draggable?: boolean
  placeholderClass?: string
}>()

const emit = defineEmits<{
  open: []
  menu: []
  dragStart: [event: DragEvent]
  reorderDrop: [event: DragEvent]
}>()
</script>

<template>
  <div class="memory-card-shell">
    <VeyqCardButton
      v-bind="$attrs"
      class="memory-card"
      :draggable="draggable"
      @click="emit('open')"
      @dragstart="emit('dragStart', $event)"
      @dragover.prevent
      @drop="emit('reorderDrop', $event)"
    >
      <img v-if="cover" class="memory-cover" :src="cover" alt="" />
      <div v-else class="memory-cover placeholder" :class="placeholderClass">
        <slot name="placeholder">...</slot>
      </div>
      <div class="memory-label">
        <div class="memory-name">{{ name }}</div>
        <slot name="meta" />
      </div>
    </VeyqCardButton>
    <VeyqIconButton
      v-if="menuLabel"
      class="card-more"
      :aria-label="menuLabel"
      :title="menuLabel"
      @click.stop="emit('menu')"
    >
      ...
    </VeyqIconButton>
  </div>
</template>
