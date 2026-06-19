<script setup lang="ts">
import type { NodeType } from '../../features/memory/createTree/memoryTree'
import MemoryObjectCard from './MemoryObjectCard.vue'

type ChildNode = { id: string; type: NodeType; name: string }

defineProps<{
  nodes: ChildNode[]
  covers: Record<string, string>
}>()

const emit = defineEmits<{
  navigate: [nodeId: string, name: string]
}>()

function nodeTypeClass(type: NodeType): string {
  return `child-cover-icon-${type.toLowerCase()}`
}
</script>

<template>
  <section class="memory-children">
    <div class="card-grid card-grid-memories">
      <MemoryObjectCard
        v-for="node in nodes"
        :key="node.id"
        class="child-card"
        :name="node.name"
        :cover="covers[node.id]"
        placeholder-class="child-cover-placeholder"
        @open="emit('navigate', node.id, node.name)"
      >
        <template #placeholder>
          <span class="child-cover-icon" :class="nodeTypeClass(node.type)" aria-hidden="true"></span>
        </template>
      </MemoryObjectCard>
    </div>
  </section>
</template>
