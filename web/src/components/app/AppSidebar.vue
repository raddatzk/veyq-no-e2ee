<script setup lang="ts">
// The shell's left rail (cr-0030). Markup + classes are the design source's TenantSidebar verbatim (brand,
// the space switcher, the Personal-space nav, profile) so layout.css styles it identically; only the data is
// ours — the tenants come from listMyTenants() (E2EE: the server only knows membership), switching is a full
// navigation to that tenant's domain, and the bottom is a sign-out (the original's ProfileMenu, favorites +
// groups are later slices).
import { ref, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink, useRouter } from 'vue-router'
import logoLightUrl from '../../assets/brand/logo-light.svg'
import { logout } from '../../features/account/login/login'
import { listMyTenants } from '../../features/spaces/scopes'
import { clearVault } from '../../app/vault'
import { activeSpace, clearActiveSpace } from '../../app/activeSpace'
import type { MyTenant } from '../../api/generated/scopes'

const { t } = useI18n()
const router = useRouter()
const tenants = ref<MyTenant[]>([])

onMounted(async () => {
  try {
    tenants.value = await listMyTenants()
  } catch {
    /* the sidebar tolerates an empty switcher */
  }
})

function tenantInitial(value: string): string {
  return value.trim().charAt(0).toUpperCase() || 'V'
}

function isActive(tenant: MyTenant): boolean {
  return tenant.tenantId === activeSpace.tenantId
}

function switchTenant(tenant: MyTenant) {
  if (isActive(tenant)) return
  // Each tenant is reached at its own domain (ADR 0002); switching is a full navigation there.
  window.location.assign(`https://${tenant.domain}`)
}

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
  <aside class="tenant-sidebar">
    <RouterLink :to="{ name: 'home' }" class="sidebar-brand" aria-label="veyq Home">
      <img class="sidebar-brand-mark" :src="logoLightUrl" alt="" aria-hidden="true" />
      <span>veyq</span>
    </RouterLink>

    <section class="sidebar-section sidebar-tenant-section">
      <div class="sidebar-section-heading">
        <p class="sidebar-section-label">{{ t('shell.spaces') }}</p>
      </div>
      <div class="sidebar-tenant-switcher">
        <div v-for="tenant in tenants" :key="tenant.tenantId" class="sidebar-tenant-item">
          <div
            v-if="isActive(tenant)"
            class="sidebar-tenant-link sidebar-tenant-link-active"
            :title="tenant.name"
          >
            <span class="sidebar-tenant-avatar"><span>{{ tenantInitial(tenant.name) }}</span></span>
            <span class="sidebar-tenant-text">
              <strong>{{ tenant.name }}</strong>
              <small>{{ tenant.domain }}</small>
            </span>
          </div>
          <button
            v-else
            class="sidebar-tenant-link"
            :title="tenant.name"
            type="button"
            @click="switchTenant(tenant)"
          >
            <span class="sidebar-tenant-avatar"><span>{{ tenantInitial(tenant.name) }}</span></span>
            <span class="sidebar-tenant-text">
              <strong>{{ tenant.name }}</strong>
              <small>{{ tenant.domain }}</small>
            </span>
          </button>
        </div>
      </div>
    </section>

    <nav class="sidebar-nav" :aria-label="t('shell.personal')">
      <RouterLink :to="{ name: 'home' }" class="sidebar-nav-link sidebar-personal-link">
        <span class="pi pi-user" />
        <span>{{ t('shell.personal') }}</span>
      </RouterLink>
    </nav>

    <section class="sidebar-section sidebar-profile">
      <button class="sidebar-nav-link" type="button" @click="signOut">
        <span class="pi pi-sign-out" />
        <span>{{ t('shell.logout') }}</span>
      </button>
    </section>
  </aside>
</template>
