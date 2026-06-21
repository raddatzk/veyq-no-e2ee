<script setup lang="ts">
import VeyqCardButton from '../ui/VeyqCardButton.vue'
import VeyqIconButton from '../ui/VeyqIconButton.vue'

defineOptions({ inheritAttrs: false })

defineProps<{
  name: string
  menuLabel?: string
  draggable?: boolean
}>()

const emit = defineEmits<{
  open: []
  menu: []
  dragStart: [event: DragEvent]
  reorderDrop: [event: DragEvent]
}>()
</script>

<template>
  <div class="folder-card-shell">
    <VeyqCardButton
      v-bind="$attrs"
      class="folder-card"
      :draggable="draggable"
      @click="emit('open')"
      @dragstart="emit('dragStart', $event)"
      @dragover.prevent
      @drop="emit('reorderDrop', $event)"
    >
      <span class="folder-icon" aria-hidden="true"></span>
      <span class="folder-name">{{ name }}</span>
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
