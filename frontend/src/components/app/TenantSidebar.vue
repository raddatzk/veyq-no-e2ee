<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import Menu from 'primevue/menu'
import { getMemoryNavigation, type MemoryScopeRoot } from '../../features/memories/api'
import { useAuthState } from '../../shared/state/useAuthState'
import ProfileMenu from './ProfileMenu.vue'

const route = useRoute()
const { t } = useI18n()
const { authState } = useAuthState()
const emit = defineEmits<{
  createTenant: []
}>()

const tenantMenu = ref()
const scopeRoots = ref<MemoryScopeRoot[]>([])
const sidebarError = ref('')
let loadSequence = 0

const canCreateTenant = computed(() => authState.user?.serverRole === 'ServerOwner')
const activeTenantName = computed(() => String(route.params.tenantName || authState.tenants[0]?.name || ''))
const activeTenant = computed(() => authState.tenants.find((tenant) => tenant.name === activeTenantName.value))
const personalRoot = computed(() => scopeRoots.value.find((scopeRoot) => scopeRoot.kind === 'USER'))
const groupRoots = computed(() => scopeRoots.value.filter((scopeRoot) => scopeRoot.kind === 'GROUP'))
const tenantMenuItems = computed(() =>
  canCreateTenant.value
    ? [
        {
          label: t('navigation.createTenant'),
          icon: 'pi pi-plus',
          command: () => emit('createTenant')
        }
      ]
    : []
)

watch(activeTenantName, loadNavigationData, { immediate: true })

async function loadNavigationData() {
  const tenantName = activeTenantName.value
  const sequence = ++loadSequence
  scopeRoots.value = []
  sidebarError.value = ''
  if (!tenantName || !authState.isAuthenticated) return

  try {
    const navigation = await getMemoryNavigation(tenantName, '/')
    if (sequence !== loadSequence) return
    scopeRoots.value = navigation.scopeRoots
  } catch {
    if (sequence === loadSequence) sidebarError.value = t('navigation.navigationLoadFailed')
  }
}

function tenantInitial(value: string): string {
  return value.trim().charAt(0).toUpperCase() || 'V'
}

function scopeIcon(scopeRoot: MemoryScopeRoot): string {
  return scopeRoot.kind === 'GROUP' ? 'pi pi-users' : 'pi pi-user'
}

function scopeLabel(scopeRoot: MemoryScopeRoot): string {
  return scopeRoot.kind === 'GROUP' ? scopeRoot.displayName : t('navigation.personal')
}

function scopeMeta(scopeRoot: MemoryScopeRoot): string {
  return scopeRoot.kind === 'GROUP' ? t('group.group') : scopeRoot.displayName
}

function toggleTenantMenu(event: MouseEvent) {
  tenantMenu.value?.toggle(event)
}
</script>

<template>
  <aside class="tenant-sidebar">
    <RouterLink to="/" class="sidebar-brand" aria-label="Veyq Home">
      <span class="sidebar-brand-mark">∞</span>
      <span>veyq</span>
    </RouterLink>

    <section class="sidebar-section">
      <div class="sidebar-section-heading">
        <p class="sidebar-section-label">{{ t('tenant.title') }}</p>
        <button
          v-if="canCreateTenant"
          class="sidebar-section-action"
          type="button"
          :title="t('navigation.createTenant')"
          :aria-label="t('navigation.createTenant')"
          @click="emit('createTenant')"
        >
          <span class="pi pi-plus" />
        </button>
      </div>
      <div class="sidebar-tenant-switcher">
        <div
          v-for="tenant in authState.tenants"
          :key="tenant.id"
          class="sidebar-tenant-item"
        >
          <div
            v-if="activeTenantName === tenant.name"
            class="sidebar-tenant-link sidebar-tenant-link-active"
            :title="tenant.displayName"
          >
            <span class="sidebar-tenant-avatar">{{ tenantInitial(tenant.displayName) }}</span>
            <span class="sidebar-tenant-text">
              <strong>{{ tenant.displayName }}</strong>
              <small>{{ tenant.domain }}</small>
            </span>
            <button
              v-if="tenantMenuItems.length"
              class="sidebar-tenant-menu-button"
              type="button"
              :aria-label="t('navigation.tenantMenuOpen')"
              @click="toggleTenantMenu"
            >
              <span class="pi pi-chevron-down" />
            </button>
          </div>
          <RouterLink
            v-else
            :to="`/${tenant.name}`"
            class="sidebar-tenant-link"
            :title="tenant.displayName"
          >
            <span class="sidebar-tenant-avatar">{{ tenantInitial(tenant.displayName) }}</span>
            <span class="sidebar-tenant-text">
              <strong>{{ tenant.displayName }}</strong>
              <small>{{ tenant.domain }}</small>
            </span>
          </RouterLink>
        </div>
        <Menu ref="tenantMenu" :model="tenantMenuItems" popup />
      </div>
    </section>

    <nav v-if="activeTenant" class="sidebar-nav" :aria-label="t('navigation.workspaceNavigation')">
      <RouterLink :to="`/${activeTenant.name}`" class="sidebar-nav-link">
        <span class="pi pi-home" />
        <span>{{ t('navigation.home') }}</span>
      </RouterLink>
      <RouterLink
        v-if="personalRoot"
        :to="`/${activeTenantName}${personalRoot.path}`"
        class="sidebar-nav-link sidebar-personal-link"
      >
        <span class="pi pi-user" />
        <span>{{ t('navigation.personal') }}</span>
      </RouterLink>
    </nav>

    <section class="sidebar-section">
      <div class="sidebar-section-heading">
        <p class="sidebar-section-label">{{ t('navigation.favorites') }}</p>
        <button
          class="sidebar-section-action"
          type="button"
          :title="t('navigation.addFavorite')"
          disabled
        >
          <span class="pi pi-plus" />
        </button>
      </div>
    </section>

    <section v-if="activeTenant" class="sidebar-section">
      <div class="sidebar-section-heading">
        <p class="sidebar-section-label">{{ t('navigation.groups') }}</p>
        <RouterLink
          v-if="activeTenant"
          :to="`/${activeTenant.name}/settings?createGroup=1`"
          class="sidebar-section-action"
          :title="t('navigation.groupCreate')"
          :aria-label="t('navigation.groupCreate')"
        >
          <span class="pi pi-plus" />
        </RouterLink>
      </div>
      <RouterLink
        v-for="scopeRoot in groupRoots"
        :key="scopeRoot.path"
        :to="`/${activeTenantName}${scopeRoot.path}`"
        class="sidebar-scope-link"
      >
        <span :class="['sidebar-scope-icon', scopeIcon(scopeRoot)]" />
        <span>
          <strong>{{ scopeLabel(scopeRoot) }}</strong>
          <small>{{ scopeMeta(scopeRoot) }}</small>
        </span>
      </RouterLink>
    </section>

    <section class="sidebar-section sidebar-profile">
      <ProfileMenu show-name />
    </section>

    <p v-if="sidebarError" class="sidebar-error">{{ sidebarError }}</p>
  </aside>
</template>
