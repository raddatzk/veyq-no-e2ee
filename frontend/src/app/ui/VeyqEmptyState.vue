<script setup lang="ts">
defineProps<{
  inline?: boolean
  loading?: boolean
}>()
</script>

<template>
  <div :class="loading ? 'veyq-loading' : inline ? 'veyq-empty-inline' : 'veyq-empty'">
    <slot />
  </div>
</template>
