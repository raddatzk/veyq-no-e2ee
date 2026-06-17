<script setup lang="ts">
import { ref } from 'vue'
import { applyTheme, session, setUnlocked } from '../session'
import { logout } from '../../features/account/login/login'
import { rotateIdentity } from '../../features/keys/rotateIdentity'
import { deviceUnlockEnabled, disableDeviceUnlock } from '../../features/account/deviceUnlock/deviceUnlock'
import { mapTilesConsent } from '../../features/account/privacy/mapConsent'
import { i18n, setLocale, t } from '../../i18n/i18n'

const emit = defineEmits<{ close: [] }>()
const host = window.location.host
const deviceUnlock = ref(deviceUnlockEnabled())
function forgetDevice() {
  disableDeviceUnlock()
  deviceUnlock.value = false
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
      <div class="settings-row">
        <span class="settings-account">
          <strong>{{ session.unlocked?.user.displayName }}</strong>
          <small>{{ session.unlocked?.user.email }}</small>
        </span>
      </div>

      <template v-if="deviceUnlock">
        <div class="settings-section">{{ t('settings.deviceUnlock') }}</div>
        <button class="btn btn-ghost settings-row" type="button" style="width: 100%; justify-content: flex-start" @click="forgetDevice">{{ t('settings.forgetDevice') }}</button>
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
