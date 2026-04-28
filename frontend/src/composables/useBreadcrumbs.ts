import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { MenuItem } from 'primevue/menuitem'
import { useAuthState } from '../shared/state/useAuthState'
import { explorerPathToTenantRoute } from '../shared/utils/paths'

function formatSegment(value: string) {
  return decodeURIComponent(value)
    .split('-')
    .filter(Boolean)
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join(' ')
}

export function useBreadcrumbs() {
  const route = useRoute()
  const router = useRouter()
  const { authState } = useAuthState()
  const activeTenantName = computed(() => String(route.params.tenantName || ''))
  const routePath = computed(() => route.fullPath.split('?')[0].split('#')[0])
  const contentPathSegments = computed(() => routePath.value.split('/').filter(Boolean).slice(1))
  const activeTenant = computed(() =>
    authState.tenants.find((tenant) => tenant.name === activeTenantName.value)
  )
  const breadcrumbHome = computed<MenuItem>(() => ({
    label: activeTenant.value?.displayName || 'Home',
    command: () => {
      void router.push(activeTenant.value ? `/${activeTenant.value.name}` : '/')
    }
  }))
  const breadcrumbItems = computed<MenuItem[]>(() =>
    contentPathSegments.value.map((segment, index) => ({
      label: formatSegment(segment),
      command: () => {
        const path = `/${contentPathSegments.value.slice(0, index + 1).join('/')}`
        void router.push(explorerPathToTenantRoute(activeTenantName.value, path))
      }
    }))
  )
  return {
    activeTenant,
    breadcrumbHome,
    breadcrumbItems
  }
}
