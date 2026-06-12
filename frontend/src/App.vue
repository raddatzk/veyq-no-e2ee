<script setup lang="ts">
import { ref, onMounted } from 'vue'

const health = ref<string>('…')

onMounted(async () => {
  try {
    const res = await fetch('/api/health')
    const body = (await res.json()) as { name: string; status: string }
    health.value = `${body.name}: ${body.status}`
  } catch {
    health.value = 'backend unreachable'
  }
})
</script>

<template>
  <main>
    <h1>veyq</h1>
    <p>backend health: {{ health }}</p>
  </main>
</template>
