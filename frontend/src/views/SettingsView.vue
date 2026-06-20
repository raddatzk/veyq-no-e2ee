<script setup lang="ts">
// User settings on the E2EE backend: profile (display name), appearance (theme/language), privacy
// (external map tiles), remembered-login, security (identity-key rotation), plus admin — create a tenant
// (Server Owner) and manage the active tenant's members (Owner). Mirrors the greenfield SettingsOverlay.
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import Button from 'primevue/button'
import Card from 'primevue/card'
import Dialog from 'primevue/dialog'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import { logout, updateProfile } from '../features/account/login/login'
import { requestEmailChange } from '../features/account/emailChange/emailChange'
import { rotateIdentity } from '../features/keys/rotateIdentity'
import { clearRememberedLogin, rememberedLoginEnabled } from '../features/account/rememberedLogin/rememberedLogin'
import { mapTilesConsent } from '../features/account/privacy/mapConsent'
import { claimTenantOwnership, createTenant, issueOwnerInvite } from '../features/admin/createTenantInviteOwner/tenant'
import { createGroupSpace } from '../features/spaces/groups/groupSpaces'
import { invalidateSpaces } from '../app/spaces'
import { session, setUnlocked } from '../app/session'
import { useThemeState, type ThemePreference } from '../shared/state/useThemeState'
import { i18n as appI18n, setLocale, t, type Locale } from '../i18n/i18n'
import MembersAdmin from '../features/memories/components/MembersAdmin.vue'
import GroupAdmin from '../features/memories/components/GroupAdmin.vue'
import ServerUsersAdmin from '../features/memories/components/ServerUsersAdmin.vue'

const router = useRouter()
const { themePreference, setThemePreference } = useThemeState()

const displayName = ref(session.unlocked?.user.displayName ?? '')
const profileSaving = ref(false)
const profileError = ref('')
const profileSaved = ref(false)
const profileDirty = computed(() => displayName.value.trim() !== (session.unlocked?.user.displayName ?? ''))

const remembered = ref(rememberedLoginEnabled())
const themes: ThemePreference[] = ['system', 'light', 'dark']
const SUPPORTED_LOCALES: readonly Locale[] = ['de', 'en']
const activeLocale = computed(() => appI18n.locale)

function forgetRememberedLogin() {
  clearRememberedLogin()
  remembered.value = false
}

async function saveProfile() {
  if (!session.unlocked) return
  profileSaving.value = true
  profileError.value = ''
  profileSaved.value = false
  try {
    const user = await updateProfile(session.apiBase, session.unlocked.sessionToken, displayName.value.trim())
    session.unlocked.user = user
    displayName.value = user.displayName
    profileSaved.value = true
  } catch (e) {
    profileError.value = e instanceof Error ? e.message : String(e)
  } finally {
    profileSaving.value = false
  }
}

// Email change is a pure identifier swap (email is not part of the KEK): request a token to the NEW address;
// the user completes it via the emailed confirmation link (EmailConfirmView). No re-wrap, no key material.
const newEmail = ref('')
const emailSending = ref(false)
const emailError = ref('')
const emailSentTo = ref('')

async function changeEmail() {
  if (!session.unlocked || !newEmail.value.trim()) return
  emailSending.value = true
  emailError.value = ''
  emailSentTo.value = ''
  try {
    await requestEmailChange(session.apiBase, session.unlocked.sessionToken, newEmail.value.trim())
    emailSentTo.value = newEmail.value.trim()
    newEmail.value = ''
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e)
    emailError.value = /409|taken/i.test(msg) ? t('emailChange.taken') : t('emailChange.failed')
  } finally {
    emailSending.value = false
  }
}

// Identity rotation re-keys every grant to a fresh identity; it leaves the in-memory session stale, so we
// sign out on success — the next login re-derives the new identity.
const rotateArmed = ref(false)
const rotating = ref(false)
const rotateError = ref('')
async function rotateIdentityNow() {
  if (!rotateArmed.value) { rotateArmed.value = true; return }
  rotating.value = true
  rotateError.value = ''
  try {
    await rotateIdentity(session.apiBase, session.unlocked!)
    await signOut()
  } catch (e) {
    rotateError.value = e instanceof Error ? e.message : String(e)
    rotating.value = false
    rotateArmed.value = false
  }
}

// --- admin: create a tenant (Server Owner) + manage the active tenant's members (Owner) ---
const isServerOwner = computed(() => session.unlocked?.user.serverRole === 'ServerOwner')
const activeTenantId = computed(() => {
  const e = session.activeSpace?.entry
  return e && e.kind === 'TENANT' && e.tenantId ? e.tenantId : ''
})
const activeGroup = computed(() => {
  const e = session.activeSpace?.entry
  return e && e.kind === 'GROUP' && e.tenantId ? { tenantId: e.tenantId, scopeId: e.scopeId } : null
})
const createTenantOpen = ref(false)
const tenantName = ref('')
const tenantDisplayName = ref('')
const tenantDomain = ref('')
const tenantBusy = ref(false)
const tenantError = ref('')

const createGroupOpen = ref(false)
const groupName = ref('')
const groupBusy = ref(false)
const groupError = ref('')

async function createGroupFlow() {
  const name = groupName.value.trim()
  if (!name || !activeTenantId.value) return
  groupBusy.value = true
  groupError.value = ''
  try {
    await createGroupSpace(session.apiBase, session.unlocked!, activeTenantId.value, name)
    invalidateSpaces()
    createGroupOpen.value = false
    groupName.value = ''
  } catch (e) {
    groupError.value = e instanceof Error ? e.message : String(e)
  } finally {
    groupBusy.value = false
  }
}

async function createTenantFlow() {
  const name = tenantName.value.trim()
  const domain = tenantDomain.value.trim()
  if (!name || !domain) return // the backend requires a unique domain (the host that resolves to the tenant)
  tenantBusy.value = true
  tenantError.value = ''
  try {
    const token = session.unlocked!.sessionToken
    // shell → owner invite → claim ownership (mint scope keypair, self-grant). Server stays blind.
    const tenant = await createTenant(session.apiBase, token, name, tenantDisplayName.value.trim() || name, domain)
    const invite = await issueOwnerInvite(session.apiBase, token, tenant.id)
    await claimTenantOwnership(session.apiBase, token, invite.inviteId, invite.bootstrapSecret, session.unlocked!.identityPublicKey.kemPublicKey)
    invalidateSpaces() // the new tenant appears in the sidebar roster on next load
    createTenantOpen.value = false
    tenantName.value = ''
    tenantDisplayName.value = ''
    tenantDomain.value = ''
  } catch (e) {
    tenantError.value = e instanceof Error ? e.message : String(e)
  } finally {
    tenantBusy.value = false
  }
}

async function signOut() {
  try {
    await logout(session.apiBase, session.unlocked!.sessionToken)
  } catch {
    /* already gone */
  }
  clearRememberedLogin()
  setUnlocked(null)
  await router.push('/login')
}
</script>

<template>
  <Card class="veyq-page settings-page">
    <template #title>{{ t('settings.title') }}</template>
    <template #content>
      <h3 class="settings-section">{{ t('settings.appearance') }}</h3>
      <div class="settings-row">
        <span>{{ t('settings.theme') }}</span>
        <div class="seg">
          <button v-for="th in themes" :key="th" type="button" :class="{ active: themePreference === th }" @click="setThemePreference(th)">
            {{ t(`settings.${th}`) }}
          </button>
        </div>
      </div>
      <div class="settings-row">
        <span>{{ t('settings.language') }}</span>
        <div class="seg">
          <button v-for="loc in SUPPORTED_LOCALES" :key="loc" type="button" :class="{ active: activeLocale === loc }" @click="setLocale(loc)">
            {{ loc === 'de' ? 'Deutsch' : 'English' }}
          </button>
        </div>
      </div>

      <h3 class="settings-section">{{ t('settings.privacy') }}</h3>
      <div class="settings-row">
        <span :title="t('settings.mapTilesHint')">{{ t('settings.mapTiles') }}</span>
        <div class="seg">
          <button type="button" :class="{ active: mapTilesConsent }" @click="mapTilesConsent = true">{{ t('settings.on') }}</button>
          <button type="button" :class="{ active: !mapTilesConsent }" @click="mapTilesConsent = false">{{ t('settings.off') }}</button>
        </div>
      </div>

      <h3 class="settings-section">{{ t('settings.account') }}</h3>
      <form class="veyq-form" @submit.prevent="saveProfile">
        <span class="settings-account">
          <strong>{{ session.unlocked?.user.displayName }}</strong>
          <small>{{ session.unlocked?.user.email }}</small>
        </span>
        <label>
          {{ t('settings.displayName') }}
          <InputText v-model="displayName" maxlength="120" autocomplete="name" />
        </label>
        <Button type="submit" :label="profileSaving ? t('settings.saving') : t('common.save')" :loading="profileSaving" :disabled="!profileDirty || !displayName.trim()" />
        <Message v-if="profileError" severity="error" :closable="false">{{ profileError }}</Message>
        <Message v-else-if="profileSaved" severity="success" :closable="false">{{ t('settings.profileSaved') }}</Message>
      </form>

      <form class="veyq-form" @submit.prevent="changeEmail">
        <label>
          {{ t('emailChange.newEmail') }}
          <InputText v-model="newEmail" type="email" maxlength="320" autocomplete="email" />
        </label>
        <Button type="submit" :label="emailSending ? t('emailChange.sending') : t('emailChange.request')" :loading="emailSending" :disabled="!newEmail.trim()" />
        <Message v-if="emailError" severity="error" :closable="false">{{ emailError }}</Message>
        <Message v-else-if="emailSentTo" severity="success" :closable="false">{{ t('emailChange.sent', { email: emailSentTo }) }}</Message>
      </form>

      <template v-if="remembered">
        <h3 class="settings-section">{{ t('settings.rememberedLogin') }}</h3>
        <Button type="button" :label="t('settings.forgetRememberedLogin')" severity="secondary" outlined @click="forgetRememberedLogin" />
      </template>

      <h3 class="settings-section">{{ t('settings.security') }}</h3>
      <p class="settings-hint">{{ t('settings.rotateIdentityHint') }}</p>
      <Message v-if="rotateError" severity="error" :closable="false">{{ rotateError }}</Message>
      <Button
        type="button"
        :label="rotating ? t('settings.rotating') : rotateArmed ? t('settings.rotateConfirm') : t('settings.rotateIdentity')"
        :severity="rotateArmed ? 'danger' : 'secondary'"
        outlined
        :loading="rotating"
        @click="rotateIdentityNow"
      />

      <template v-if="isServerOwner || activeTenantId || activeGroup">
        <h3 class="settings-section">{{ t('tenants.manageTitle') }}</h3>
        <div class="admin-actions">
          <Button v-if="isServerOwner" type="button" :label="t('tenants.create')" icon="pi pi-plus" severity="secondary" outlined @click="createTenantOpen = true" />
          <Button v-if="activeTenantId" type="button" :label="t('groups.create')" icon="pi pi-users" severity="secondary" outlined @click="createGroupOpen = true" />
        </div>
        <MembersAdmin v-if="activeTenantId" :tenant-id="activeTenantId" />
        <template v-if="activeGroup">
          <h4 class="settings-section">{{ t('groups.members') }}</h4>
          <GroupAdmin :tenant-id="activeGroup.tenantId" :scope-id="activeGroup.scopeId" @deleted="router.push('/')" />
        </template>
        <template v-if="isServerOwner">
          <h4 class="settings-section">Server</h4>
          <ServerUsersAdmin />
        </template>
      </template>

      <h3 class="settings-section">{{ t('settings.instance') }}</h3>
      <p class="settings-hint">{{ session.apiBase || 'same origin' }}</p>

      <div class="settings-signout">
        <Button type="button" :label="t('settings.signOut')" icon="pi pi-sign-out" severity="secondary" text @click="signOut" />
      </div>

      <Dialog v-model:visible="createTenantOpen" :header="t('tenants.createTitle')" modal class="tenant-dialog" :style="{ width: '26rem' }">
        <form class="veyq-form" @submit.prevent="createTenantFlow">
          <label>{{ t('tenants.name') }}<InputText v-model="tenantName" autofocus :disabled="tenantBusy" /></label>
          <label>{{ t('settings.displayName') }}<InputText v-model="tenantDisplayName" :disabled="tenantBusy" /></label>
          <label>{{ t('tenants.domain') }}<InputText v-model="tenantDomain" :disabled="tenantBusy" :placeholder="t('tenants.domainHint')" /></label>
          <Message v-if="tenantError" severity="error" :closable="false">{{ tenantError }}</Message>
          <div class="veyq-dialog-action-row">
            <Button type="button" :label="t('common.cancel')" severity="secondary" text :disabled="tenantBusy" @click="createTenantOpen = false" />
            <Button type="submit" :label="t('common.create')" icon="pi pi-check" :loading="tenantBusy" :disabled="!tenantName.trim() || !tenantDomain.trim()" />
          </div>
        </form>
      </Dialog>

      <Dialog v-model:visible="createGroupOpen" :header="t('groups.createTitle')" modal class="tenant-dialog" :style="{ width: '26rem' }">
        <form class="veyq-form" @submit.prevent="createGroupFlow">
          <label>{{ t('groups.name') }}<InputText v-model="groupName" autofocus :disabled="groupBusy" /></label>
          <Message v-if="groupError" severity="error" :closable="false">{{ groupError }}</Message>
          <div class="veyq-dialog-action-row">
            <Button type="button" :label="t('common.cancel')" severity="secondary" text :disabled="groupBusy" @click="createGroupOpen = false" />
            <Button type="submit" :label="t('common.create')" icon="pi pi-check" :loading="groupBusy" :disabled="!groupName.trim()" />
          </div>
        </form>
      </Dialog>
    </template>
  </Card>
</template>

<style scoped>
.settings-page { max-width: 640px; margin: 1rem auto; }
.settings-section { margin: 1.25rem 0 0.5rem; font-size: 0.8rem; text-transform: uppercase; letter-spacing: 0.05em; opacity: 0.6; }
.settings-row { display: flex; align-items: center; justify-content: space-between; gap: 1rem; padding: 0.4rem 0; }
.settings-account { display: flex; flex-direction: column; margin-bottom: 0.5rem; }
.settings-account small { opacity: 0.6; }
.settings-hint { opacity: 0.7; font-size: 0.9rem; margin: 0.25rem 0 0.5rem; }
.admin-actions { display: flex; gap: 0.5rem; flex-wrap: wrap; margin-bottom: 0.75rem; }
.seg { display: inline-flex; border: 1px solid var(--p-content-border-color, rgba(127,127,127,0.3)); border-radius: 8px; overflow: hidden; }
.seg button { background: none; border: none; padding: 0.35rem 0.75rem; cursor: pointer; color: inherit; }
.seg button.active { background: var(--p-primary-color, #6366f1); color: var(--p-primary-contrast-color, #fff); }
.settings-signout { margin-top: 1.5rem; }
</style>
