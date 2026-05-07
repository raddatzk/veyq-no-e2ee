import { reactive } from 'vue'
import type { TenantSummary, UserSummary } from './api/generated'

export type { TenantSummary, UserSummary }

export const authState = reactive({
  loading: true,
  isAuthenticated: false,
  userLabel: '',
  user: null as UserSummary | null,
  tenants: [] as TenantSummary[]
})
