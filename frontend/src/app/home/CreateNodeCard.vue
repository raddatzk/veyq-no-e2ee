<script setup lang="ts">
import VeyqCardButton from '../ui/VeyqCardButton.vue'

defineProps<{
  row?: boolean
}>()

const emit = defineEmits<{
  create: []
}>()
</script>

<template>
  <VeyqCardButton class="create-card" :class="{ 'create-card-row': row }" @click="emit('create')">
    <span class="create-plus" aria-hidden="true">+</span>
    <slot />
  </VeyqCardButton>
</template>
