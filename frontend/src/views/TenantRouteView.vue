<script setup lang="ts">
import { computed, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import HomeView from './HomeView.vue'
import TenantView from './TenantView.vue'
import SettingsView from './SettingsView.vue'
import UserProfileView from './UserProfileView.vue'
import { useAuthState } from '../shared/state/useAuthState'
import { isSettingsPath, memoryPathToTenantRoute, routePathToMemoryPath } from '../shared/utils/paths'

const route = useRoute()
const router = useRouter()
const { authState } = useAuthState()
const tenantName = computed(() => authState.activeTenantName || authState.tenants[0]?.name || '')
const path = computed(() => routePathToMemoryPath(route.params.pathMatch))
const firstSegment = computed(() => path.value.split('/').filter(Boolean)[0] || '')
const ownProfileSegment = computed(() => authState.user?.permalink ? `~${authState.user.permalink}` : '')
const isSettingsRoute = computed(() => isSettingsPath(path.value))
const ownProfileRedirectPath = computed(() =>
  tenantName.value && ownProfileSegment.value && firstSegment.value === ownProfileSegment.value
    ? memoryPathToTenantRoute(tenantName.value, path.value.replace(/^\/~[^/]+/, '/~me'))
    : ''
)
const isProfileRoute = computed(() =>
  firstSegment.value.startsWith('~') &&
  firstSegment.value !== '~me' &&
  firstSegment.value !== ownProfileSegment.value
)

watch(
  ownProfileRedirectPath,
  (redirectPath) => {
    if (!redirectPath) return
    void router.replace({ path: redirectPath, query: route.query, hash: route.hash })
  },
  { immediate: true }
)
</script>

<template>
  <HomeView v-if="!tenantName" />
  <SettingsView v-else-if="isSettingsRoute" />
  <UserProfileView v-else-if="isProfileRoute" />
  <TenantView v-else />
</template>
