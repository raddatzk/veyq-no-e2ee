<script setup lang="ts">
// The shell's left rail (cr-0030/0031). Markup + classes are the design source's TenantSidebar verbatim (brand,
// the space switcher, the Personal-space nav, the ProfileMenu) so layout.css styles it identically; only the
// data is ours — the tenants come from listMyTenants() (E2EE: the server only knows membership), switching is a
// full navigation to that tenant's domain, and the bottom is the ProfileMenu (theme + language + sign-out).
// Favorites + groups are later slices.
import { ref, computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink, useRoute } from 'vue-router'
import logoDarkUrl from '../../assets/brand/logo-dark.svg'
import logoLightUrl from '../../assets/brand/logo-light.svg'
import { listMyTenants } from '../../features/spaces/scopes'
import { activeSpace } from '../../app/activeSpace'
import { favoritesStore } from '../../app/favoritesStore'
import { useThemeState } from '../../shared/state/useThemeState'
import { treeStore } from '../../features/memory/treeStore'
import { nodeById } from '../../features/memory/browse'
import { hashForNode, parseHash, TENANT_SCOPE, isPersonalScope, personalScope } from '../../features/memory/location'
import { getMyProfile } from '../../features/account/profile/profile'
import { NodeKind } from '../../api/generated/content'
import type { TreeNode } from '../../features/memory/tree'
import type { MyTenant } from '../../api/generated/scopes'
import ProfileMenu from './ProfileMenu.vue'

const { t } = useI18n()
const route = useRoute()
const { resolvedTheme } = useThemeState()
const tenants = ref<MyTenant[]>([])

const brandLogoUrl = computed(() => (resolvedTheme.value === 'dark' ? logoDarkUrl : logoLightUrl))

// The active in-tenant space, from the URL's leading fragment token (token-less = the shared tenant space,
// `~me` = personal — ADR 0019). Drives both which sidebar entry is highlighted and how favorite hashes build.
const activeScope = computed(() => parseHash(route.hash).scope)
const tenantActive = computed(() => activeScope.value === TENANT_SCOPE)
const personalActive = computed(() => isPersonalScope(activeScope.value))

// The caller's own per-tenant username builds the Personal link `#~<username>` (ADR 0023); until it resolves (or
// if there's no profile in this tenant), the `~me` fallback still routes to the personal scope.
const myUsername = ref('')
const personalHash = computed(() => `#${personalScope(myUsername.value)}`)

// The shared tenant space is labelled with the tenant's own name (membership metadata — no sealed name to
// decrypt); falls back to a generic "Shared" until the tenant list resolves.
const tenantLabel = computed(
  () => tenants.value.find((tn) => tn.tenantId === activeSpace.tenantId)?.name ?? t('shell.shared'),
)

// The pinned Memories, resolved against the local decrypted tree (a favorite whose node is gone just drops).
const favoriteMemories = computed(() =>
  [...favoritesStore.ids]
    .map((id) => nodeById(treeStore.nodes, id))
    .filter((n): n is TreeNode => !!n && n.kind === NodeKind.Memory)
    .map((n) => ({ id: n.id, name: n.name, hash: hashForNode(treeStore.nodes, n.id, activeScope.value) })),
)

onMounted(async () => {
  try {
    tenants.value = await listMyTenants()
  } catch {
    /* the sidebar tolerates an empty switcher */
  }
  try {
    myUsername.value = (await getMyProfile()).username
  } catch {
    /* no profile in this tenant yet → the ~me fallback still routes to the personal scope */
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
</script>

<template>
  <aside class="tenant-sidebar">
    <RouterLink :to="{ name: 'home', hash: '' }" class="sidebar-brand" aria-label="veyq Home">
      <img class="sidebar-brand-mark" :src="brandLogoUrl" alt="" aria-hidden="true" />
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
              <!-- Awaiting access: a grant-less member is on this tenant but has no key yet (cr-0077). The
                   token-less home fell back to Personal (activeSpace.tenantUnavailable); a key-holder delivers
                   the grant automatically (cr-0076), then this clears on the next entry. -->
              <small v-if="activeSpace.tenantUnavailable" class="sidebar-tenant-awaiting">
                {{ t('members.awaitingAccess') }}
              </small>
              <small v-else>{{ tenant.domain }}</small>
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

    <nav class="sidebar-nav" :aria-label="t('shell.spaces')">
      <!-- Both entries share the `/` path (only the fragment differs), so we drive the active class from the
           leading token ourselves rather than RouterLink's path-based `router-link-active`. The shared tenant
           space is the token-less home; Personal rides `#~me`. -->
      <RouterLink v-slot="{ href, navigate }" :to="{ name: 'home', hash: '' }" custom>
        <a
          :href="href"
          class="sidebar-nav-link sidebar-tenant-space-link"
          :class="{ 'router-link-active': tenantActive }"
          @click="navigate"
        >
          <span class="pi pi-users" />
          <span>{{ tenantLabel }}</span>
        </a>
      </RouterLink>
      <RouterLink v-slot="{ href, navigate }" :to="{ name: 'home', hash: personalHash }" custom>
        <a
          :href="href"
          class="sidebar-nav-link sidebar-personal-link"
          :class="{ 'router-link-active': personalActive }"
          @click="navigate"
        >
          <span class="pi pi-user" />
          <span>{{ t('shell.personal') }}</span>
        </a>
      </RouterLink>
    </nav>

    <section v-if="favoriteMemories.length" class="sidebar-section sidebar-favorites-section">
      <div class="sidebar-section-heading">
        <p class="sidebar-section-label">{{ t('shell.favorites') }}</p>
      </div>
      <RouterLink
        v-for="memory in favoriteMemories"
        :key="memory.id"
        :to="{ name: 'home', hash: memory.hash }"
        class="sidebar-scope-link sidebar-favorite-link"
      >
        <span class="sidebar-favorite-cover"><span class="pi pi-images" /></span>
        <span><strong>{{ memory.name }}</strong></span>
      </RouterLink>
    </section>

    <section class="sidebar-section sidebar-profile">
      <ProfileMenu show-name />
    </section>
  </aside>
</template>
