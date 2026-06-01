import { computed } from 'vue'
import { useRouter } from 'vue-router'
import type { MenuItem } from 'primevue/menuitem'
import { useAuthState } from '../shared/state/useAuthState'

export function useBreadcrumbs() {
  const router = useRouter()
  const { authState } = useAuthState()
  const activeTenantName = computed(() => authState.activeTenantName || authState.tenants[0]?.name || '')
  const activeTenant = computed(() =>
    authState.tenants.find((tenant) => tenant.name === activeTenantName.value)
  )
  const fallbackBreadcrumb = computed<MenuItem>(() => ({
    label: activeTenant.value?.displayName || 'veyq',
    command: () => {
      void router.push('/')
    }
  }))
  return {
    activeTenant,
    fallbackBreadcrumb
  }
}
