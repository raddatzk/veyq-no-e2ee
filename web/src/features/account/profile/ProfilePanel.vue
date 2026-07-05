<script setup lang="ts">
// uc.account.profile (web, cr-0083 / ADR 0023) — the "Your profile" rubric of the settings hub: edit your
// per-tenant `username` (plaintext handle) + your real name (first + last), which is **sealed under K_tenant**
// on-device before it leaves the browser (operator-blind). The tenant key is opened on demand (openTenantKey)
// so the panel works from any active space; a member without a tenant grant yet can still set their username.
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import { getMyProfile, updateMyProfile, sealName, unsealName, openTenantKey } from './profile'
import { memberError } from '../../spaces/members'

const { t } = useI18n()
const router = useRouter()

const username = ref('')
const firstName = ref('')
const lastName = ref('')
const loading = ref(true)
const loadError = ref(false)
const saving = ref(false)
const error = ref('')
const notice = ref('')
// K_tenant is held outside any reactive proxy — key material must not flow through Vue reactivity.
let tenantKey: Uint8Array | null = null

onMounted(async () => {
  try {
    const profile = await getMyProfile()
    username.value = profile.username
    tenantKey = await openTenantKey()
    if (tenantKey) {
      const name = unsealName(tenantKey, profile.sealedName)
      firstName.value = name?.firstName ?? ''
      lastName.value = name?.lastName ?? ''
    }
  } catch (e) {
    const { status } = await memberError(e)
    if (status === 401) return void router.push({ name: 'login' })
    loadError.value = true
  } finally {
    loading.value = false
  }
})

async function save() {
  if (!username.value.trim()) return
  error.value = ''
  notice.value = ''
  saving.value = true
  try {
    // Seal the real name under K_tenant when we hold it; otherwise update only the username (the name can be
    // added once the member's grant lands and they re-open this panel).
    const sealedName =
      tenantKey && (firstName.value.trim() || lastName.value.trim())
        ? sealName(tenantKey, { firstName: firstName.value, lastName: lastName.value })
        : undefined
    await updateMyProfile({ username: username.value.trim().toLowerCase(), sealedName })
    notice.value = t('profile.saved')
  } catch (e) {
    const { status, code } = await memberError(e)
    if (status === 401) return void router.push({ name: 'login' })
    error.value = code === 'username_taken' ? t('profile.usernameTaken') : t('profile.saveFailed')
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <section class="veyq-surface settings-panel">
    <section class="settings-form">
      <div class="settings-section-header">
        <div>
          <h2>{{ t('profile.title') }}</h2>
          <p>{{ t('profile.sub') }}</p>
        </div>
      </div>

      <p v-if="loading" class="settings-muted">{{ t('profile.loading') }}</p>
      <Message v-else-if="loadError" severity="error" :closable="false">{{ t('profile.loadFailed') }}</Message>

      <form v-else class="veyq-form" @submit.prevent="save">
        <label>{{ t('profile.username') }}
          <InputText v-model="username" name="username" autocomplete="username" required />
          <small class="field-hint">{{ t('profile.usernameHint') }}</small>
        </label>
        <div class="name-row">
          <label>{{ t('profile.firstName') }}<InputText v-model="firstName" autocomplete="given-name" /></label>
          <label>{{ t('profile.lastName') }}<InputText v-model="lastName" autocomplete="family-name" /></label>
        </div>
        <small v-if="!tenantKey" class="field-hint">{{ t('profile.noNameYet') }}</small>

        <Message v-if="notice" severity="success" :closable="false">{{ notice }}</Message>
        <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>
        <div class="veyq-dialog-action-row">
          <Button type="submit" :label="t('profile.save')" icon="pi pi-check" :loading="saving" :disabled="!username.trim()" />
        </div>
      </form>
    </section>
  </section>
</template>

<style scoped>
.settings-muted {
  margin: 0;
  color: var(--app-muted);
}
.name-row {
  display: flex;
  gap: 1rem;
}
.name-row label {
  flex: 1;
  min-width: 0;
}
</style>
