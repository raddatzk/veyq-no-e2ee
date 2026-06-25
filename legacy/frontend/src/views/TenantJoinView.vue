<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Card from 'primevue/card'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import Password from 'primevue/password'
import TieredMenu from 'primevue/tieredmenu'
import PasskeyNameDialog from '../components/auth/PasskeyNameDialog.vue'
import RegistrationPrivacyOptIns from '../components/auth/RegistrationPrivacyOptIns.vue'
import { bootstrap } from '../api/entrypointClient'
import {
  getTenantRegistrationInvite,
  joinTenantRegistrationInvite,
  joinTenantRegistrationInviteWithPasskey
} from '../api/tenantRegistrationInviteClient'
import { setLocale, SUPPORTED_LOCALES, type SupportedLocale } from '../i18n'
import { listMyPasskeys, updateMyPasskey, type PasskeySummary } from '../features/settings/api'
import { useAuthState } from '../shared/state/useAuthState'
import { useThemeState, type ThemePreference } from '../shared/state/useThemeState'
import { getErrorMessage } from '../shared/utils/errors'

const route = useRoute()
const router = useRouter()
const { locale, t } = useI18n()
const { authState, setAuthenticated } = useAuthState()
const { themePreference, setThemePreference } = useThemeState()

const token = computed(() => String(route.hash || route.params.token || '').replace(/^#/, ''))
const tenantDisplayName = ref('')
const tenantLogoUrl = ref('')
const email = ref('')
const displayName = ref('')
const password = ref('')
const authMethod = ref<'passkey' | 'password'>('passkey')
const loading = ref(false)
const submitting = ref(false)
const savingPasskeyName = ref(false)
const error = ref('')
const namingPasskey = ref<PasskeySummary | null>(null)
const showPrivacyOptIns = ref(false)
const needsPrivacyOptIn = ref(false)
const languageMenu = ref<{ toggle: (event: Event) => void } | null>(null)
const themeMenu = ref<{ toggle: (event: Event) => void } | null>(null)

const existingAccount = computed(() => authState.isAuthenticated && Boolean(authState.user?.email))
const canSubmitJoin = computed(() => {
  if (loading.value || submitting.value) return false
  if (!email.value.trim()) return false
  if (existingAccount.value) return true
  if (!displayName.value.trim()) return false
  if (authMethod.value === 'password' && !password.value) return false
  return true
})
const languageMenuItems = computed(() =>
  SUPPORTED_LOCALES.map((nextLocale) => ({
    label: t(`navigation.locales.${nextLocale}`),
    icon: locale.value === nextLocale ? 'pi pi-check' : 'pi pi-circle',
    command: () => setLocale(nextLocale as SupportedLocale)
  }))
)
const themeMenuItems = computed(() =>
  (['system', 'light', 'dark'] as ThemePreference[]).map((preference) => ({
    label: t(`navigation.themes.${preference}`),
    icon: themePreference.value === preference ? 'pi pi-check' : 'pi pi-circle',
    command: () => setThemePreference(preference)
  }))
)

onMounted(() => {
  if (authState.user?.email) email.value = authState.user.email
  void loadInvite()
})

async function loadInvite() {
  error.value = ''
  loading.value = true
  try {
    const metadata = await getTenantRegistrationInvite(token.value)
    tenantDisplayName.value = metadata.tenantDisplayName
    tenantLogoUrl.value = metadata.tenantLogoUrl || ''
  } catch (caught) {
    error.value = getErrorMessage(caught, t('auth.tenantJoinInvalid'))
  } finally {
    loading.value = false
  }
}

async function submit() {
  error.value = ''
  const inviteEmail = email.value.trim()
  if (!inviteEmail) {
    error.value = t('auth.emailRequired')
    return
  }
  if (!existingAccount.value && !displayName.value.trim()) {
    error.value = t('auth.displayNameRequired')
    return
  }
  if (!existingAccount.value && authMethod.value === 'password' && !password.value) {
    error.value = t('auth.passwordRequired')
    return
  }
  submitting.value = true
  const createsAccount = !existingAccount.value
  const withPasskey = createsAccount && authMethod.value === 'passkey'
  try {
    if (existingAccount.value) {
      await joinTenantRegistrationInvite(token.value, inviteEmail)
    } else if (withPasskey) {
      await joinTenantRegistrationInviteWithPasskey(token.value, inviteEmail, displayName.value.trim())
    } else {
      await joinTenantRegistrationInvite(token.value, inviteEmail, displayName.value.trim(), password.value)
    }
    const entrypoint = await bootstrap()
    if (entrypoint.user) {
      setAuthenticated(entrypoint.user, entrypoint.tenants)
    }
    needsPrivacyOptIn.value = createsAccount
    if (withPasskey) {
      await showLatestPasskeyNameDialog()
    } else {
      await continueAfterRegistration()
    }
  } catch (caught) {
    error.value = getErrorMessage(caught, t('auth.tenantJoinFailed'))
  } finally {
    submitting.value = false
  }
}

async function showLatestPasskeyNameDialog() {
  try {
    const summary = await listMyPasskeys()
    namingPasskey.value = summary.passkeys[0] ?? null
  } catch {
    namingPasskey.value = null
  }
  if (!namingPasskey.value) await continueAfterRegistration()
}

async function saveJoinPasskeyName(displayName: string) {
  if (!namingPasskey.value) return
  savingPasskeyName.value = true
  error.value = ''
  try {
    await updateMyPasskey(namingPasskey.value.passkeyId, displayName)
    namingPasskey.value = null
    await continueAfterRegistration()
  } catch (caught) {
    error.value = getErrorMessage(caught, t('profile.passkeyRenameFailed'))
  } finally {
    savingPasskeyName.value = false
  }
}

async function skipJoinPasskeyName() {
  if (!namingPasskey.value) return
  namingPasskey.value = null
  await continueAfterRegistration()
}

async function continueAfterRegistration() {
  if (needsPrivacyOptIn.value) {
    showPrivacyOptIns.value = true
  } else {
    await finishJoin()
  }
}

async function finishPrivacyOptIns() {
  showPrivacyOptIns.value = false
  needsPrivacyOptIn.value = false
  await finishJoin()
}

async function finishJoin() {
  await router.push('/')
}

function toggleLanguageMenu(event: Event) {
  languageMenu.value?.toggle(event)
}

function toggleThemeMenu(event: Event) {
  themeMenu.value?.toggle(event)
}
</script>

<template>
  <Card class="login-card">
    <template #title>{{ t('auth.tenantJoinTitle') }}</template>
    <template #content>
      <div class="join-preference-actions">
        <Button
          type="button"
          icon="pi pi-language"
          severity="secondary"
          outlined
          :aria-label="t('navigation.language')"
          :title="t('navigation.language')"
          @click="toggleLanguageMenu"
        />
        <TieredMenu ref="languageMenu" :model="languageMenuItems" popup />
        <Button
          type="button"
          icon="pi pi-palette"
          severity="secondary"
          outlined
          :aria-label="t('navigation.theme')"
          :title="t('navigation.theme')"
          @click="toggleThemeMenu"
        />
        <TieredMenu ref="themeMenu" :model="themeMenuItems" popup />
      </div>
      <RegistrationPrivacyOptIns v-if="showPrivacyOptIns" @complete="finishPrivacyOptIns" />
      <form v-else class="veyq-form" @submit.prevent="submit">
        <div v-if="tenantDisplayName" class="invite-tenant-header">
          <img v-if="tenantLogoUrl" :src="tenantLogoUrl" alt="" />
          <div>
            <h2>{{ tenantDisplayName }}</h2>
            <p>{{ t(existingAccount ? 'auth.tenantJoinExistingCopy' : 'auth.tenantJoinNewCopy') }}</p>
          </div>
        </div>

        <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>
        <Message v-else-if="loading" severity="info" :closable="false">{{ t('common.loading') }}</Message>

        <label>
          {{ t('common.email') }}
          <InputText v-model="email" type="email" autocomplete="email" :readonly="existingAccount" required />
        </label>

        <template v-if="!existingAccount">
          <label>
            {{ t('auth.displayName') }}
            <InputText v-model="displayName" autocomplete="name" required />
          </label>
          <div class="auth-method-switch" role="tablist" :aria-label="t('auth.authMethod')">
            <button
              type="button"
              :class="{ active: authMethod === 'passkey' }"
              role="tab"
              :aria-selected="authMethod === 'passkey'"
              @click="authMethod = 'passkey'"
            >
              {{ t('auth.passkey') }}
            </button>
            <button
              type="button"
              :class="{ active: authMethod === 'password' }"
              role="tab"
              :aria-selected="authMethod === 'password'"
              @click="authMethod = 'password'"
            >
              {{ t('common.password') }}
            </button>
          </div>
          <label v-if="authMethod === 'password'">
            {{ t('common.password') }}
            <Password
              v-model="password"
              :feedback="false"
              toggle-mask
              input-class="w-full"
              autocomplete="new-password"
              required
            />
          </label>
        </template>

        <Button
          type="submit"
          :label="
            existingAccount
              ? t('auth.tenantJoinExistingSubmit')
              : authMethod === 'passkey'
                ? t('auth.accountCreateWithPasskey')
                : t('auth.tenantJoinNewSubmit')
          "
          :icon="!existingAccount && authMethod === 'passkey' ? 'pi pi-key' : 'pi pi-arrow-right'"
          :loading="submitting"
          :disabled="!canSubmitJoin"
        />
      </form>
      <PasskeyNameDialog
        :visible="Boolean(namingPasskey)"
        :passkey="namingPasskey"
        :saving="savingPasskeyName"
        @update:visible="(visible) => { if (!visible) skipJoinPasskeyName() }"
        @save="saveJoinPasskeyName"
        @skip="skipJoinPasskeyName"
      />
    </template>
  </Card>
</template>
