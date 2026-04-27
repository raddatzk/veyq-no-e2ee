import { reactive } from 'vue'

export type TenantSummary = {
  id: number
  name: string
  displayName: string
  role: 'TENANT_OWNER' | 'TENANT_MEMBER'
  admins: UserSummary[]
}

export type UserSummary = {
  userId: number
  email: string
  displayName: string
  serverRole: 'SERVER_OWNER' | 'SERVER_MEMBER'
}

export const authState = reactive({
  loading: true,
  setupRequired: false,
  isAuthenticated: false,
  userLabel: '',
  user: null as UserSummary | null,
  tenants: [] as TenantSummary[]
})
