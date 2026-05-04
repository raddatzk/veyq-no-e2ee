import { reactive } from 'vue'
import type { TenantSummary } from './api/generated/tenants'
import type { UserSummary } from './api/generated/users'

export type { TenantSummary, UserSummary }

export const authState = reactive({
  loading: true,
  isAuthenticated: false,
  userLabel: '',
  user: null as UserSummary | null,
  tenants: [] as TenantSummary[]
})
