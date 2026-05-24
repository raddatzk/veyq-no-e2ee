<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import TenantView from './TenantView.vue'
import SettingsView from './SettingsView.vue'
import UserProfileView from './UserProfileView.vue'
import { isSettingsPath, routePathToMemoryPath } from '../shared/utils/paths'

const route = useRoute()
const path = computed(() => routePathToMemoryPath(route.params.pathMatch))
const isSettingsRoute = computed(() => isSettingsPath(path.value))
const isProfileRoute = computed(() => {
  const firstSegment = path.value.split('/').filter(Boolean)[0] || ''
  return firstSegment.startsWith('~') && firstSegment !== '~me'
})
</script>

<template>
  <SettingsView v-if="isSettingsRoute" />
  <UserProfileView v-else-if="isProfileRoute" />
  <TenantView v-else />
</template>
