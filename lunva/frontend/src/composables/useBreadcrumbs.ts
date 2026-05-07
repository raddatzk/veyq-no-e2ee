import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { MenuItem } from 'primevue/menuitem'
import { useAuthState } from '../shared/state/useAuthState'

export function useBreadcrumbs() {
  const route = useRoute()
  const router = useRouter()
  const { authState } = useAuthState()
  const activeTenantName = computed(() => String(route.params.tenantName || ''))
  const activeTenant = computed(() =>
    authState.tenants.find((tenant) => tenant.name === activeTenantName.value)
  )
  const fallbackBreadcrumb = computed<MenuItem>(() => ({
    label: activeTenant.value?.displayName || 'Lunva',
    command: () => {
      void router.push(activeTenant.value ? `/${activeTenant.value.name}` : '/')
    }
  }))
  return {
    activeTenant,
    fallbackBreadcrumb
  }
}
