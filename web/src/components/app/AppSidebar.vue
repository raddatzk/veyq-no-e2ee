<script setup lang="ts">
// The shell's left rail (cr-0030, simplified port of legacy TenantSidebar): the app brand, the Personal-space
// nav, and sign-out. Multi-space switching, favorites and search are later slices.
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { logout } from '../../features/account/login/login'
import { clearVault } from '../../app/vault'
import { clearActiveSpace } from '../../app/activeSpace'

const { t } = useI18n()
const router = useRouter()

async function signOut() {
  try {
    await logout()
  } finally {
    clearVault()
    clearActiveSpace()
    await router.push({ name: 'login' })
  }
}
</script>

<template>
  <aside class="app-sidebar">
    <div class="app-brand">{{ t('shell.appName') }}</div>
    <nav class="app-nav">
      <RouterLink :to="{ name: 'home' }" class="app-nav-item" active-class="app-nav-item-active">
        <i class="pi pi-images" aria-hidden="true" />
        <span>{{ t('shell.personal') }}</span>
      </RouterLink>
    </nav>
    <button class="app-nav-item app-logout" type="button" @click="signOut">
      <i class="pi pi-sign-out" aria-hidden="true" />
      <span>{{ t('shell.logout') }}</span>
    </button>
  </aside>
</template>

<style scoped>
.app-sidebar {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 16px 12px;
  background: #fff;
  border-right: 1px solid var(--app-line);
  height: 100%;
}
.app-brand {
  font-weight: 700;
  font-size: 1.1rem;
  padding: 8px 12px;
}
.app-nav {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.app-nav-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  border-radius: var(--app-radius-sm);
  color: var(--app-text);
  text-decoration: none;
  background: none;
  border: none;
  font: inherit;
  cursor: pointer;
  text-align: left;
}
.app-nav-item:hover {
  background: var(--app-bg);
}
.app-nav-item-active {
  background: var(--app-bg);
  font-weight: 600;
}
.app-logout {
  color: var(--app-muted);
}
</style>
