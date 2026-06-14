<script setup lang="ts">
import { logout } from '../features/account/login/login'
import { session, setUnlocked } from './session'

async function signOut() {
  try {
    await logout(session.apiBase, session.unlocked!.sessionToken)
  } catch {
    // session may already be gone server-side; clear locally regardless
  }
  setUnlocked(null)
}
</script>

<template>
  <div class="auth-screen">
    <div class="auth-card">
      <p class="auth-wordmark">veyq</p>
      <h1 class="auth-title">Eingeloggt</h1>
      <p class="auth-sub">{{ session.unlocked?.user.displayName }} · {{ session.unlocked?.user.email }}</p>
      <p class="field-hint">Slice 2 ersetzt das durch den Space-Switcher + MemoryHome.</p>
      <button class="btn btn-ghost" type="button" style="margin-top: 1rem; width: 100%" @click="signOut">
        Abmelden
      </button>
    </div>
  </div>
</template>
