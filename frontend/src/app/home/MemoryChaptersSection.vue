<script setup lang="ts">
import type { NodeType } from '../../features/memory/createTree/memoryTree'

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
      <button v-for="node in nodes" :key="node.id" class="memory-card child-card" @click="emit('navigate', node.id, node.name)">
        <img v-if="covers[node.id]" class="memory-cover" :src="covers[node.id]" alt="" />
        <div v-else class="memory-cover placeholder child-cover-placeholder">
          <span class="child-cover-icon" :class="nodeTypeClass(node.type)" aria-hidden="true"></span>
        </div>
        <div class="memory-label"><div class="memory-name">{{ node.name }}</div></div>
      </button>
    </div>
  </section>
</template>
