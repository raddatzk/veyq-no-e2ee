import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { authState } from '../authState'

function formatSegment(value: string) {
  return decodeURIComponent(value)
    .split('-')
    .filter(Boolean)
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join(' ')
}

export function useBreadcrumbs() {
  const route = useRoute()
  const activeTenantName = computed(() => String(route.params.tenantName || ''))
  const routePath = computed(() => route.fullPath.split('?')[0].split('#')[0])
  const contentPathSegments = computed(() => routePath.value.split('/').filter(Boolean).slice(1))
  const activeTenant = computed(() => authState.tenants.find((tenant) => tenant.name === activeTenantName.value))
  const breadcrumbHome = computed(() => {
    if (activeTenant.value) {
      return {
        label: activeTenant.value.displayName,
        to: `/${activeTenant.value.name}`
      }
    }
    return { label: 'Home', to: '/' }
  })
  const breadcrumbItems = computed(() =>
    contentPathSegments.value.map((segment, index) => ({
      label: formatSegment(segment),
      to: `/${[activeTenantName.value, ...contentPathSegments.value.slice(0, index + 1)].filter(Boolean).join('/')}`
    }))
  )

  return {
    activeTenant,
    breadcrumbHome,
    breadcrumbItems
  }
}
