<script setup lang="ts">
import { ref } from 'vue'
import { RouterLink, RouterView, useRouter } from 'vue-router'
import { authState } from './authState'
import { logout } from './api/authClient'

const open = ref(false)
const router = useRouter()

async function doLogout() {
  await logout()
  authState.isAuthenticated = false
  authState.username = ''
  open.value = false
  await router.push('/')
}
</script>

<template>
  <div class="app-shell">
    <header class="topbar">
      <RouterLink to="/" class="brand">Lunva</RouterLink>
      <nav class="menu">
        <RouterLink v-if="!authState.isAuthenticated" to="/login" class="nav-link">Login</RouterLink>
        <div v-else class="avatar-wrap">
          <button class="avatar" @click="open = !open">{{ authState.username.charAt(0).toUpperCase() }}</button>
          <div v-if="open" class="dropdown">
            <button class="dropdown-item" @click="doLogout">Logout</button>
          </div>
        </div>
      </nav>
    </header>

    <main class="content">
      <RouterView />
    </main>
  </div>
</template>
