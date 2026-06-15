<script setup lang="ts">
import { applyTheme, session, setUnlocked } from '../session'
import { logout } from '../../features/account/login/login'

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
        <h2 class="settings-title">Einstellungen</h2>
        <button class="btn btn-ghost" type="button" @click="emit('close')">Fertig</button>
      </div>

      <div class="settings-section">Darstellung</div>
      <div class="settings-row">
        <span>Theme</span>
        <div class="seg">
          <button :class="{ active: session.theme === 'dark' }" @click="applyTheme('dark')">Dunkel</button>
          <button :class="{ active: session.theme === 'light' }" @click="applyTheme('light')">Hell</button>
        </div>
      </div>

      <div class="settings-section">Account</div>
      <div class="settings-row">
        <span class="settings-account">
          <strong>{{ session.unlocked?.user.displayName }}</strong>
          <small>{{ session.unlocked?.user.email }}</small>
        </span>
      </div>

      <div class="settings-section">Instanz</div>
      <div class="settings-row"><span>{{ host }}</span></div>

      <button class="btn btn-ghost settings-signout" type="button" @click="signOut">Abmelden</button>
    </div>
  </div>
</template>
