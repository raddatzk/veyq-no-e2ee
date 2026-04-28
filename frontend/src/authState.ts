import { reactive } from 'vue'

export type TenantSummary = {
  id: number
  name: string
  displayName: string
  role: 'TenantOwner' | 'TenantMember'
  admins: UserSummary[]
}

export type UserSummary = {
  userId: number
  email: string
  displayName: string
  serverRole: 'ServerOwner' | 'ServerMember'
}

export const authState = reactive({
  loading: true,
  isAuthenticated: false,
  userLabel: '',
  user: null as UserSummary | null,
  tenants: [] as TenantSummary[]
})
