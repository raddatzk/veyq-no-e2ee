import { reactive } from 'vue'

export type TenantSummary = {
  id: number
  slug: string
  name: string
  role: 'owner' | 'member'
}

export const authState = reactive({
  loading: true,
  setupRequired: false,
  isAuthenticated: false,
  userLabel: '',
  tenants: [] as TenantSummary[]
})
