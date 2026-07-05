<script setup lang="ts">
// The shell's left rail (cr-0030/0031). Markup + classes are the design source's TenantSidebar verbatim (brand,
// the space switcher, the Personal-space nav, the ProfileMenu) so layout.css styles it identically; only the
// data is ours — the tenants come from listMyTenants() (E2EE: the server only knows membership), switching is a
// full navigation to that tenant's domain, and the bottom is the ProfileMenu (theme + language + sign-out).
// Favorites + groups are later slices.
import { ref, computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import Dialog from 'primevue/dialog'
import logoDarkUrl from '../../assets/brand/logo-dark.svg'
import logoLightUrl from '../../assets/brand/logo-light.svg'
import { listMyTenants, listMyGroups, type GroupSpace } from '../../features/spaces/scopes'
import { activeSpace } from '../../app/activeSpace'
import CreateGroupDialog from '../../features/spaces/CreateGroupDialog.vue'
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
const router = useRouter()
const { resolvedTheme } = useThemeState()
const tenants = ref<MyTenant[]>([])

const brandLogoUrl = computed(() => (resolvedTheme.value === 'dark' ? logoDarkUrl : logoLightUrl))

// The active in-tenant space, from the URL's leading fragment token (token-less = the shared tenant space,
// `~me` = personal — ADR 0019). Drives both which sidebar entry is highlighted and how favorite hashes build.
const activeScope = computed(() => parseHash(route.hash).scope)
const tenantActive = computed(() => activeScope.value === TENANT_SCOPE)
const personalActive = computed(() => isPersonalScope(activeScope.value))

// The member's groups (en.spaces.group-scope, cr-0032): names decrypted on-device (the server only ever stored
// ciphertext). A group is entered via the `#@<id>` fragment; the active one is highlighted from the leading token.
const groups = ref<GroupSpace[]>([])
function groupHash(scopeId: string): string {
  return `#@${scopeId}`
}
function groupActive(scopeId: string): boolean {
  return activeScope.value === `@${scopeId}`
}
async function loadGroups() {
  try {
    groups.value = await listMyGroups()
  } catch {
    /* the switcher tolerates an empty groups section */
  }
}

// Create-group dialog. On success: refresh the list + switch into the new group (`#@<id>`).
const createGroupOpen = ref(false)
async function onGroupCreated(scopeId: string) {
  await loadGroups()
  void router.push({ name: 'home', hash: groupHash(scopeId) })
}

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
  await loadGroups()
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

// Mobile navigation (cr-0084): below the compact threshold the inline nav is hidden, so a hamburger opens a
// bottom-sheet with the full navigation (tenant switch + Shared/Personal + favorites) — ported from the legacy
// design source. Groups join it when the groups epic lands.
const mobileMenuOpen = ref(false)
function closeMobileMenu() {
  mobileMenuOpen.value = false
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

    <!-- Groups (en.spaces.group-scope, cr-0032). The header + "＋" is always present so the first group is
         creatable; the group links appear as they land. Desktop renders `.desktop-group-link`; the compact rail
         renders `.compact-group-nav` (layout.css toggles which is visible). Active class is driven from the
         leading token (all groups share the `/` path, so RouterLink's path-based active can't distinguish). -->
    <section class="sidebar-section sidebar-groups-section">
      <div class="sidebar-section-heading">
        <p class="sidebar-section-label">{{ t('shell.groups') }}</p>
        <button
          class="sidebar-section-action"
          type="button"
          :title="t('shell.groupCreate')"
          :aria-label="t('shell.groupCreate')"
          @click="createGroupOpen = true"
        >
          <span class="pi pi-plus" />
        </button>
      </div>
      <RouterLink
        v-for="group in groups"
        :key="group.scopeId"
        v-slot="{ href, navigate }"
        :to="{ name: 'home', hash: groupHash(group.scopeId) }"
        custom
      >
        <a
          :href="href"
          class="sidebar-scope-link desktop-group-link"
          :class="{ 'router-link-active': groupActive(group.scopeId) }"
          @click="navigate"
        >
          <span class="sidebar-scope-icon pi pi-users" />
          <span><strong>{{ group.name }}</strong></span>
        </a>
      </RouterLink>
      <div class="compact-group-nav">
        <span class="compact-group-divider" aria-hidden="true" />
        <div class="compact-group-links">
          <RouterLink
            v-for="group in groups"
            :key="group.scopeId"
            v-slot="{ href, navigate }"
            :to="{ name: 'home', hash: groupHash(group.scopeId) }"
            custom
          >
            <a
              :href="href"
              class="sidebar-scope-link compact-group-link"
              :class="{ 'router-link-active': groupActive(group.scopeId) }"
              @click="navigate"
            >
              <span class="sidebar-scope-icon pi pi-users" />
              <span><strong>{{ group.name }}</strong></span>
            </a>
          </RouterLink>
        </div>
      </div>
    </section>

    <section class="sidebar-section sidebar-profile">
      <ProfileMenu show-name />
    </section>

    <!-- Mobile: the inline nav is CSS-hidden below the compact threshold — this hamburger opens the full nav
         as a bottom sheet (cr-0084, ported from the legacy design source; the styles were already vendored). -->
    <button class="mobile-navigation-button" type="button" :aria-label="t('shell.menuOpen')" @click="mobileMenuOpen = true">
      <span class="pi pi-bars" />
    </button>

    <Dialog v-model:visible="mobileMenuOpen" modal position="bottom" class="tenant-dialog mobile-nav-dialog" :header="t('shell.menu')">
      <div class="mobile-nav-sheet">
        <section class="mobile-nav-section">
          <h2>{{ t('shell.spaces') }}</h2>
          <div class="mobile-nav-list">
            <button
              v-for="tenant in tenants"
              :key="tenant.tenantId"
              class="mobile-nav-item"
              :class="{ 'mobile-nav-item-active': isActive(tenant) }"
              type="button"
              @click="switchTenant(tenant); closeMobileMenu()"
            >
              <span class="sidebar-tenant-avatar"><span>{{ tenantInitial(tenant.name) }}</span></span>
              <span><strong>{{ tenant.name }}</strong><small>{{ tenant.domain }}</small></span>
            </button>
          </div>
        </section>

        <section class="mobile-nav-section">
          <h2>{{ tenantLabel }}</h2>
          <div class="mobile-nav-list">
            <RouterLink
              :to="{ name: 'home', hash: '' }"
              class="mobile-nav-item"
              :class="{ 'mobile-nav-item-active': tenantActive }"
              @click="closeMobileMenu"
            >
              <span class="sidebar-scope-icon pi pi-users" /><span>{{ t('shell.shared') }}</span>
            </RouterLink>
            <RouterLink
              :to="{ name: 'home', hash: personalHash }"
              class="mobile-nav-item"
              :class="{ 'mobile-nav-item-active': personalActive }"
              @click="closeMobileMenu"
            >
              <span class="sidebar-scope-icon pi pi-user" /><span>{{ t('shell.personal') }}</span>
            </RouterLink>
          </div>
        </section>

        <section class="mobile-nav-section">
          <h2>{{ t('shell.groups') }}</h2>
          <div class="mobile-nav-list">
            <RouterLink
              v-for="group in groups"
              :key="group.scopeId"
              :to="{ name: 'home', hash: groupHash(group.scopeId) }"
              class="mobile-nav-item"
              :class="{ 'mobile-nav-item-active': groupActive(group.scopeId) }"
              @click="closeMobileMenu"
            >
              <span class="sidebar-scope-icon pi pi-users" /><span><strong>{{ group.name }}</strong></span>
            </RouterLink>
            <button class="mobile-nav-item" type="button" @click="createGroupOpen = true; closeMobileMenu()">
              <span class="sidebar-scope-icon pi pi-plus" /><span>{{ t('shell.groupCreate') }}</span>
            </button>
          </div>
        </section>

        <section v-if="favoriteMemories.length" class="mobile-nav-section">
          <h2>{{ t('shell.favorites') }}</h2>
          <div class="mobile-nav-list">
            <RouterLink
              v-for="memory in favoriteMemories"
              :key="memory.id"
              :to="{ name: 'home', hash: memory.hash }"
              class="mobile-nav-item"
              @click="closeMobileMenu"
            >
              <span class="sidebar-scope-icon pi pi-images" /><span><strong>{{ memory.name }}</strong></span>
            </RouterLink>
          </div>
        </section>
      </div>
    </Dialog>

    <CreateGroupDialog v-if="createGroupOpen" @done="onGroupCreated" @close="createGroupOpen = false" />
  </aside>
</template>
