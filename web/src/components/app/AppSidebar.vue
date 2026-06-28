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
  gap: 1rem;
  height: 100%;
  padding: 1.05rem 1rem;
  border-right: 1px solid var(--app-line);
  background: var(--app-chrome-pattern);
  box-shadow:
    inset -1px 0 0 rgb(255 255 255 / 0.74),
    10px 0 34px rgb(15 23 42 / 0.05);
  backdrop-filter: blur(22px);
  overflow: auto;
}
.app-brand {
  padding: 0.2rem 0.35rem;
  color: var(--app-text);
  font-size: 1.35rem;
  font-weight: 850;
}
.app-nav {
  flex: 1;
  display: grid;
  gap: 0.35rem;
  align-content: start;
}
.app-nav-item {
  display: grid;
  grid-template-columns: 2.1rem minmax(0, 1fr);
  align-items: center;
  gap: 0.55rem;
  min-height: 2.45rem;
  padding: 0.35rem;
  border: 0;
  border-radius: var(--app-radius-sm);
  background: transparent;
  color: var(--app-control-text);
  text-decoration: none;
  font: inherit;
  cursor: pointer;
  text-align: left;
}
.app-nav-item:hover,
.app-nav-item-active {
  background: var(--app-hover);
}
.app-nav-item-active {
  color: var(--app-text);
  font-weight: 600;
}
.app-nav-item > .pi {
  display: grid;
  place-items: center;
  width: 2.1rem;
  height: 2.1rem;
  border-radius: 10px;
  background: var(--app-accent-soft);
  color: var(--app-accent);
  font-weight: 800;
}
.app-logout {
  color: var(--app-muted);
}
.app-logout > .pi {
  background: transparent;
  color: var(--app-muted);
}
</style>
