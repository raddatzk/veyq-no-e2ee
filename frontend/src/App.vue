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
  authState.userLabel = ''
  authState.tenants = []
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
          <button class="avatar" @click="open = !open">{{ authState.userLabel.charAt(0).toUpperCase() }}</button>
          <div v-if="open" class="dropdown">
            <button class="dropdown-item" @click="doLogout">Logout</button>
          </div>
        </div>
      </nav>
    </header>

    <main class="content">
      <RouterView />
    </main>

    <aside v-if="authState.isAuthenticated && authState.tenants.length" class="tenant-rail">
      <RouterLink
        v-for="tenant in authState.tenants"
        :key="tenant.id"
        :to="`/${tenant.slug}`"
        class="tenant-dot"
        :title="tenant.name"
      >
        {{ tenant.name.charAt(0).toUpperCase() }}
      </RouterLink>
    </aside>
  </div>
</template>
