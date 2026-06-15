<script setup lang="ts">
import { applyTheme, session, setUnlocked } from '../session'
import { logout } from '../../features/account/login/login'
import { i18n, setLocale, t } from '../../i18n/i18n'

const emit = defineEmits<{ close: [] }>()
const host = window.location.host

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

      <div class="settings-section">{{ t('settings.account') }}</div>
      <div class="settings-row">
        <span class="settings-account">
          <strong>{{ session.unlocked?.user.displayName }}</strong>
          <small>{{ session.unlocked?.user.email }}</small>
        </span>
      </div>

      <div class="settings-section">{{ t('settings.instance') }}</div>
      <div class="settings-row"><span>{{ host }}</span></div>

      <button class="btn btn-ghost settings-signout" type="button" @click="signOut">{{ t('settings.signOut') }}</button>
    </div>
  </div>
</template>
