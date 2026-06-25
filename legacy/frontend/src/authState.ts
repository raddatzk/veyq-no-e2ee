// Legacy auth state — now a thin reactive BRIDGE over the new E2EE `session` store. Many legacy
// components still read `authState.user` / `.isAuthenticated` / `.userLabel`; this keeps them working
// while each is migrated to read `session` directly. `tenants`/`activeTenantName` are populated by the
// space layer (sidebar/routing) during the port. Authentication itself lives in app/session.ts.
import { reactive, watchEffect } from 'vue'
import { session } from './app/session'

export type UserSummary = {
  id: string
  userId: string // legacy alias for id (older components read user.userId)
  email: string
  displayName: string
  serverRole: string
  username: string
  // Legacy optional fields some shell/settings components still read. They have no E2EE meaning anymore
  // (avatars/passkeys/TOTP are gone) and stay undefined; kept so deferred components still typecheck.
  avatarUrl?: string
  pendingEmail?: string
}
export type TenantSummary = {
  id: string
  name: string
  displayName: string
  domain?: string
  logoUrl?: string
  role?: string
}

export const authState = reactive({
  loading: true,
  isAuthenticated: false,
  userLabel: '',
  user: null as UserSummary | null,
  tenants: [] as TenantSummary[],
  activeTenantName: '',
})

// Mirror the unlocked session into the legacy authState shape.
watchEffect(() => {
  const u = session.unlocked
  authState.isAuthenticated = u !== null
  if (u) {
    authState.user = {
      id: u.user.id,
      userId: u.user.id,
      email: u.user.email,
      displayName: u.user.displayName,
      serverRole: u.user.serverRole,
      username: u.user.username,
    }
    authState.userLabel = u.user.displayName || u.user.email
  } else {
    authState.user = null
    authState.userLabel = ''
    authState.tenants = []
    authState.activeTenantName = ''
  }
})
