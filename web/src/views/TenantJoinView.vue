<script setup lang="ts">
// uc.admin.create-tenant Slice B (cr-0010) — the invited owner accepts and activates the tenant. The
// bootstrap secret rides in the URL #fragment (never sent until accept). preview tells us whether the
// invited email already has an account: a NEW owner registers cold (Emergency Kit) here, an EXISTING owner
// signs in + unlocks. Either way the client cold-births the TENANT scope and seals the first grant to its
// own identity — the scope key never leaves the device.
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Card from 'primevue/card'
import Checkbox from 'primevue/checkbox'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import Password from 'primevue/password'
import { previewInvite, acceptInvite, registerAndAccept, scopeGrant, tenantError } from '../features/admin/createTenant/tenants'
import { buildAccount, type BuiltAccount } from '../features/account/register/accountBirth'
import { login } from '../features/account/login/login'
import { unlockMasterKey } from '../features/account/unlock/unlock'
import { identityPrivate } from '../app/vault'
import { setupApi } from '../api/client'
import { fromB64std } from '../shared/base64'
import { hybridPublic } from '../shared/crypto/v1'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()

type Phase = 'loading' | 'invalid' | 'expired' | 'register' | 'kit' | 'signin' | 'done'
const phase = ref<Phase>('loading')
const error = ref('')
const busy = ref(false)

const tenantName = ref('')
const domain = ref('')
const ownerEmail = ref('')

// register form
const displayName = ref('')
const password = ref('')
const secretKeyDisplay = ref('')
const kitSaved = ref(false)
// sign-in form
const secretKey = ref('')

const secret = String(route.hash || '').replace(/^#/, '')
// Non-reactive: keep the freshly born identity's Uint8Arrays out of Vue's deep proxy.
let built: BuiltAccount | null = null

onMounted(async () => {
  if (!secret) {
    phase.value = 'invalid'
    return
  }
  try {
    const p = await previewInvite(secret)
    tenantName.value = p.tenantName
    domain.value = p.domain
    ownerEmail.value = p.ownerEmail
    phase.value = p.hasAccount ? 'signin' : 'register'
  } catch (e) {
    const { status } = await tenantError(e)
    phase.value = status === 410 ? 'expired' : 'invalid'
  }
})

/** Register step 1: build the account on-device and show its Emergency Kit before activating. */
async function prepareRegistration() {
  if (!displayName.value.trim() || password.value.length === 0) return
  error.value = ''
  busy.value = true
  try {
    const instanceSalt = fromB64std((await setupApi().getSetupStatus()).instanceSalt)
    built = buildAccount(ownerEmail.value, password.value, instanceSalt)
    secretKeyDisplay.value = built.emergencyKit.secretKey
    phase.value = 'kit'
  } catch {
    error.value = t('tenant.join.failed')
  } finally {
    busy.value = false
  }
}

/** Register step 2: cold-birth the scope grant to the new identity and register-and-accept. */
async function finishRegistration() {
  if (!built || !kitSaved.value) return
  error.value = ''
  busy.value = true
  try {
    const grant = scopeGrant({ x: built.identity.pub.kemX, m: built.identity.pub.kemM })
    await registerAndAccept(secret, displayName.value.trim(), built.material, grant)
    phase.value = 'done'
  } catch (e) {
    error.value = await mapError(e)
  } finally {
    busy.value = false
  }
}

/** Existing owner: sign in, unlock (identity key), then seal the grant to it and accept. */
async function signInAndAccept() {
  if (password.value.length === 0 || secretKey.value.trim().length === 0) return
  error.value = ''
  busy.value = true
  try {
    await login(ownerEmail.value, password.value)
    await unlockMasterKey(password.value, secretKey.value.trim())
    const priv = identityPrivate()
    if (!priv) throw new Error('locked')
    const grant = scopeGrant(hybridPublic(priv))
    await acceptInvite(secret, grant)
    phase.value = 'done'
  } catch (e) {
    error.value = await mapError(e)
  } finally {
    busy.value = false
  }
}

async function mapError(e: unknown): Promise<string> {
  const { status, code } = await tenantError(e)
  if (status === 410) return t('tenant.join.expired')
  if (status === 404) return t('tenant.join.invalid')
  if (code === 'email_taken') return t('tenant.join.emailTaken')
  if (code === 'not_invited_owner') return t('tenant.join.wrongOwner')
  return t('tenant.join.failed')
}
</script>

<template>
  <Card class="join-card">
    <template #title>{{ t('tenant.join.title') }}</template>
    <template #content>
      <p v-if="phase === 'loading'" class="muted">{{ t('tenant.join.loading') }}</p>

      <Message v-else-if="phase === 'invalid'" severity="error" :closable="false">{{ t('tenant.join.invalid') }}</Message>
      <Message v-else-if="phase === 'expired'" severity="warn" :closable="false">{{ t('tenant.join.expired') }}</Message>

      <template v-else-if="phase === 'register'">
        <p class="veyq-form-copy">{{ t('tenant.join.invitedTo', { tenant: tenantName, domain }) }}</p>
        <p class="veyq-form-copy">{{ t('tenant.join.registerSub', { email: ownerEmail }) }}</p>
        <form class="veyq-form" @submit.prevent="prepareRegistration">
          <label>{{ t('tenant.join.displayName') }}<InputText v-model="displayName" autocomplete="name" required /></label>
          <label>{{ t('common.password') }}<Password v-model="password" :feedback="false" toggleMask inputId="join-password" /></label>
          <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>
          <Button type="submit" :label="t('tenant.join.continue')" icon="pi pi-arrow-right" :loading="busy"
                  :disabled="!displayName.trim() || password.length === 0" />
        </form>
      </template>

      <template v-else-if="phase === 'kit'">
        <Message severity="info" :closable="false">{{ t('tenant.join.saveKitTitle') }}</Message>
        <p class="veyq-form-copy">{{ t('tenant.join.saveKitSub') }}</p>
        <pre class="secret-key">{{ secretKeyDisplay }}</pre>
        <label class="kit-confirm">
          <Checkbox v-model="kitSaved" :binary="true" /><span>{{ t('tenant.join.kitConfirm') }}</span>
        </label>
        <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>
        <Button :label="t('tenant.join.join')" icon="pi pi-check" :loading="busy" :disabled="!kitSaved" @click="finishRegistration" />
      </template>

      <template v-else-if="phase === 'signin'">
        <p class="veyq-form-copy">{{ t('tenant.join.invitedTo', { tenant: tenantName, domain }) }}</p>
        <p class="veyq-form-copy">{{ t('tenant.join.signinSub', { email: ownerEmail }) }}</p>
        <form class="veyq-form" @submit.prevent="signInAndAccept">
          <label>{{ t('common.password') }}<Password v-model="password" :feedback="false" toggleMask inputId="signin-password" /></label>
          <label>{{ t('unlock.secretKey') }}<InputText v-model="secretKey" autocomplete="off" required /></label>
          <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>
          <Button type="submit" :label="t('tenant.join.join')" icon="pi pi-check" :loading="busy"
                  :disabled="password.length === 0 || secretKey.trim().length === 0" />
        </form>
      </template>

      <template v-else-if="phase === 'done'">
        <Message severity="success" :closable="false">{{ t('tenant.join.done', { tenant: tenantName }) }}</Message>
        <p class="veyq-form-copy">{{ t('tenant.join.doneNote') }}</p>
        <Button :label="t('login.title')" icon="pi pi-sign-in" @click="router.push({ name: 'login' })" />
      </template>
    </template>
  </Card>
</template>

<style scoped>
.muted { opacity: 0.7; }
.secret-key {
  font-family: ui-monospace, monospace;
  background: var(--p-content-border-color, rgba(255, 255, 255, 0.08));
  padding: 0.6rem 0.8rem;
  border-radius: 0.5rem;
  overflow-wrap: anywhere;
  white-space: pre-wrap;
}
.kit-confirm { display: flex; align-items: center; gap: 0.5rem; }
</style>
