<script setup lang="ts">
import { computed, ref } from 'vue'
import { applyTheme, session, setUnlocked } from '../session'
import { logout, updateProfile } from '../../features/account/login/login'
import { rotateIdentity } from '../../features/keys/rotateIdentity'
import { clearRememberedLogin, rememberedLoginEnabled } from '../../features/account/rememberedLogin/rememberedLogin'
import { mapTilesConsent } from '../../features/account/privacy/mapConsent'
import { i18n, setLocale, t } from '../../i18n/i18n'

const emit = defineEmits<{ close: [] }>()
const host = window.location.host
const rememberedLogin = ref(rememberedLoginEnabled())
const displayName = ref(session.unlocked?.user.displayName ?? '')
const profileSaving = ref(false)
const profileError = ref('')
const profileSaved = ref(false)
const profileDirty = computed(() => displayName.value.trim() !== (session.unlocked?.user.displayName ?? ''))
function forgetRememberedLogin() {
  clearRememberedLogin()
  rememberedLogin.value = false
}

async function saveProfile() {
  if (!session.unlocked) return
  profileSaving.value = true
  profileError.value = ''
  profileSaved.value = false
  try {
    const user = await updateProfile(session.apiBase, session.unlocked.sessionToken, displayName.value)
    session.unlocked.user = user
    displayName.value = user.displayName
    profileSaved.value = true
  } catch (e) {
    profileError.value = (e as Error).message
  } finally {
    profileSaving.value = false
  }
}

// Identity rotation: re-keys every grant to a fresh identity (auth/master key unchanged). It leaves the
// in-memory session's identity stale, so on success we sign out — the next login re-derives the new identity.
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
    rotateError.value = (e as Error).message
    rotating.value = false
    rotateArmed.value = false
  }
}

async function signOut() {
  try {
    await logout(session.apiBase, session.unlocked!.sessionToken)
  } catch {
    /* already gone */
  }
  setUnlocked(null)
}
</script>

<template>
  <div class="settings-backdrop" @click.self="emit('close')">
    <div class="settings-panel">
      <div class="settings-head">
        <h2 class="settings-title">{{ t('settings.title') }}</h2>
        <button class="btn btn-ghost" type="button" @click="emit('close')">{{ t('common.done') }}</button>
      </div>

      <div class="settings-section">{{ t('settings.appearance') }}</div>
      <div class="settings-row">
        <span>{{ t('settings.theme') }}</span>
        <div class="seg">
          <button :class="{ active: session.theme === 'dark' }" @click="applyTheme('dark')">{{ t('settings.dark') }}</button>
          <button :class="{ active: session.theme === 'light' }" @click="applyTheme('light')">{{ t('settings.light') }}</button>
        </div>
      </div>
      <div class="settings-row">
        <span>{{ t('settings.language') }}</span>
        <div class="seg">
          <button :class="{ active: i18n.locale === 'de' }" @click="setLocale('de')">Deutsch</button>
          <button :class="{ active: i18n.locale === 'en' }" @click="setLocale('en')">English</button>
        </div>
      </div>

      <div class="settings-section">{{ t('settings.privacy') }}</div>
      <div class="settings-row">
        <span :title="t('settings.mapTilesHint')">{{ t('settings.mapTiles') }}</span>
        <div class="seg">
          <button :class="{ active: mapTilesConsent }" @click="mapTilesConsent = true">{{ t('settings.on') }}</button>
          <button :class="{ active: !mapTilesConsent }" @click="mapTilesConsent = false">{{ t('settings.off') }}</button>
        </div>
      </div>

      <div class="settings-section">{{ t('settings.account') }}</div>
      <form class="settings-profile" @submit.prevent="saveProfile">
        <span class="settings-account">
          <strong>{{ session.unlocked?.user.displayName }}</strong>
          <small>{{ session.unlocked?.user.email }}</small>
        </span>
        <label class="settings-field">
          <span>{{ t('settings.displayName') }}</span>
          <input v-model="displayName" maxlength="120" autocomplete="name" />
        </label>
        <div class="settings-profile-actions">
          <button class="btn btn-primary" type="submit" :disabled="profileSaving || !profileDirty || !displayName.trim()">
            {{ profileSaving ? t('settings.saving') : t('common.save') }}
          </button>
        </div>
        <div v-if="profileError" class="auth-msg auth-msg-error">{{ profileError }}</div>
        <div v-else-if="profileSaved" class="auth-msg auth-msg-success">{{ t('settings.profileSaved') }}</div>
      </form>

      <template v-if="rememberedLogin">
        <div class="settings-section">{{ t('settings.rememberedLogin') }}</div>
        <button class="btn btn-ghost settings-row" type="button" style="width: 100%; justify-content: flex-start" @click="forgetRememberedLogin">{{ t('settings.forgetRememberedLogin') }}</button>
      </template>

      <div class="settings-section">{{ t('settings.security') }}</div>
      <p class="settings-hint">{{ t('settings.rotateIdentityHint') }}</p>
      <div v-if="rotateError" class="auth-msg auth-msg-error">{{ rotateError }}</div>
      <button
        class="btn btn-ghost settings-row"
        type="button"
        style="width: 100%; justify-content: flex-start"
        :style="{ color: rotateArmed ? 'var(--app-danger)' : undefined }"
        :disabled="rotating"
        @click="rotateIdentityNow"
      >
        {{ rotating ? t('settings.rotating') : rotateArmed ? t('settings.rotateConfirm') : t('settings.rotateIdentity') }}
      </button>

      <div class="settings-section">{{ t('settings.instance') }}</div>
      <div class="settings-row"><span>{{ host }}</span></div>

      <button class="btn btn-ghost settings-signout" type="button" @click="signOut">{{ t('settings.signOut') }}</button>
    </div>
  </div>
</template>
