<script setup lang="ts">
// uc.account.accept-terms (web, cr-0114, ADR 0035) — the member terms accept-gate. A standalone full-screen card
// (no app shell): the router bounces any member whose accepted_terms_version is below the active tenant's current
// version here, and they can only ACCEPT (records it server-side → returns them where they were headed) or SIGN
// OUT. Operator-blind: the terms are the operator's PUBLIC policy; acceptance is coarse operational metadata.
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Card from 'primevue/card'
import Message from 'primevue/message'
import {
  getTenantTerms,
  acceptTenantTerms,
  tenantError,
  type TenantTerms,
} from '../features/admin/createTenant/tenants'
import { activeSpace, markTermsAccepted, clearActiveSpace } from '../app/activeSpace'
import { takeRoute } from '../app/pendingRoute'
import { logout } from '../features/account/login/login'
import { resetServerRole } from '../features/admin/serverRole'

const { t, locale } = useI18n()
const router = useRouter()

type Phase = 'loading' | 'ready' | 'loadFailed' | 'done'
const phase = ref<Phase>('loading')
const terms = ref<TenantTerms | null>(null)
const error = ref('')
const busy = ref(false)

// The terms text in the active UI locale, falling back to the other locale so a tenant that filled only one is
// never shown blank (the server applies the same fallback intent; here we pick client-side from the returned map).
const body = computed(() => {
  const tm = terms.value?.terms
  if (!tm) return ''
  const preferred = locale.value === 'de' ? tm.de : tm.en
  return preferred || tm.en || tm.de || ''
})

onMounted(async () => {
  // Reached directly (a reload on /terms) before the shell resolved a tenant → bounce to home; the shell enters
  // the tenant and the router re-fires the gate with a tenant in hand.
  if (!activeSpace.tenantId) {
    void router.replace({ name: 'home' })
    return
  }
  await load()
})

async function load() {
  phase.value = 'loading'
  error.value = ''
  try {
    terms.value = await getTenantTerms(activeSpace.tenantId!)
    phase.value = 'ready'
  } catch (e) {
    const { status } = await tenantError(e)
    if (status === 401) return void router.push({ name: 'login' })
    phase.value = 'loadFailed'
  }
}

async function accept() {
  if (!terms.value) return
  error.value = ''
  busy.value = true
  try {
    const updated = await acceptTenantTerms(activeSpace.tenantId!, terms.value.termsVersion)
    markTermsAccepted(updated.acceptedVersion) // clears termsStale() so the guard lets the member back in
    phase.value = 'done'
    // Return the member to where the guard intercepted them (remembered deep link), else the shell home.
    await router.push(takeRoute() ?? { name: 'home' })
  } catch (e) {
    const { status, code } = await tenantError(e)
    if (status === 401) return void router.push({ name: 'login' })
    if (code === 'terms_version_changed') {
      // The operator bumped the terms while this was open — re-load + re-present the newer version.
      error.value = t('terms.versionChanged')
      await load()
    } else {
      error.value = t('terms.acceptFailed')
    }
  } finally {
    busy.value = false
  }
}

// The only way out without accepting: sign out (mirrors ProfileMenu). Declining records nothing; the member
// stays out of the shell.
async function signOut() {
  try {
    await logout()
  } finally {
    resetServerRole()
    clearActiveSpace()
    await router.push({ name: 'login' })
  }
}
</script>

<template>
  <Card class="terms-card">
    <template #title>{{ t('terms.gateTitle') }}</template>
    <template #content>
      <p v-if="phase === 'loading'" class="muted">{{ t('terms.loading') }}</p>

      <Message v-else-if="phase === 'loadFailed'" severity="error" :closable="false">{{ t('terms.loadFailed') }}</Message>

      <template v-else-if="phase === 'ready'">
        <p class="veyq-form-copy">{{ t('terms.gateIntro') }}</p>
        <div class="terms-body">{{ body }}</div>
        <Message v-if="error" severity="warn" :closable="false">{{ error }}</Message>
        <div class="terms-actions">
          <Button :label="t('terms.accept')" icon="pi pi-check" :loading="busy" @click="accept" />
          <Button
            :label="t('terms.signOut')"
            icon="pi pi-sign-out"
            severity="secondary"
            text
            :disabled="busy"
            @click="signOut"
          />
        </div>
      </template>

      <Message v-else-if="phase === 'done'" severity="success" :closable="false">{{ t('terms.accepted') }}</Message>
    </template>
  </Card>
</template>

<style scoped>
.muted {
  opacity: 0.7;
}
.terms-body {
  max-height: 50vh;
  overflow-y: auto;
  white-space: pre-wrap;
  padding: 0.85rem 1rem;
  margin: 0.75rem 0;
  border: 1px solid var(--app-border, var(--p-content-border-color, rgba(127, 127, 127, 0.25)));
  border-radius: 0.6rem;
  font-size: 0.92rem;
  line-height: 1.5;
}
.terms-actions {
  display: flex;
  gap: 0.75rem;
  align-items: center;
  margin-top: 0.5rem;
}
</style>
