<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import TenantView from './TenantView.vue'
import SettingsView from './SettingsView.vue'
import UserProfileView from './UserProfileView.vue'
import { useAuthState } from '../shared/state/useAuthState'
import { isSettingsPath, routePathToMemoryPath } from '../shared/utils/paths'

const route = useRoute()
const { authState } = useAuthState()
const path = computed(() => routePathToMemoryPath(route.params.pathMatch))
const isSettingsRoute = computed(() => isSettingsPath(path.value))
const isProfileRoute = computed(() => {
  const firstSegment = path.value.split('/').filter(Boolean)[0] || ''
  const ownProfileSegment = authState.user?.permalink ? `~${authState.user.permalink}` : ''
  return firstSegment.startsWith('~') && firstSegment !== '~me' && firstSegment !== ownProfileSegment
})
</script>

<template>
  <SettingsView v-if="isSettingsRoute" />
  <UserProfileView v-else-if="isProfileRoute" />
  <TenantView v-else />
</template>
