<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import TenantView from './TenantView.vue'
import SettingsView from './SettingsView.vue'
import { isSettingsPath, routePathToMemoryPath } from '../shared/utils/paths'

const route = useRoute()
const path = computed(() => routePathToMemoryPath(route.params.pathMatch))
const isSettingsRoute = computed(() => isSettingsPath(path.value))
</script>

<template>
  <SettingsView v-if="isSettingsRoute" />
  <TenantView v-else />
</template>
