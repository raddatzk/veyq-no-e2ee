<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import Dialog from 'primevue/dialog'
import Menu from 'primevue/menu'
import type { MenuItem } from 'primevue/menuitem'
import logoDarkUrl from '../../assets/brand/logo-dark.svg'
import logoLightUrl from '../../assets/brand/logo-light.svg'
import { getMemoryNavigationContext, type Memory, type MemoryScopeRoot } from '../../features/memories/api'
import { memoryFavoritesRefreshToken } from '../../features/memories/composables/useMemoryFavoritesRefresh'
import MemoryCoverImage from '../../features/memories/components/MemoryCoverImage.vue'
import { API_BASE } from '../../shared/api/httpClient'
import { useAuthState } from '../../shared/state/useAuthState'
import { useThemeState } from '../../shared/state/useThemeState'
import { createRafResizeObserver, type RafResizeObserver } from '../../shared/utils/resizeObserver'
import ProfileMenu from './ProfileMenu.vue'

const router = useRouter()
const { t } = useI18n()
const { authState, setActiveTenantName } = useAuthState()
const { resolvedTheme } = useThemeState()
const props = defineProps<{
  navigationRefreshKey?: number
}>()
const emit = defineEmits<{
  createTenant: []
  createGroup: []
}>()

const groupMenu = ref()
const mobileMenuOpen = ref(false)
const compactGroupLinks = ref<HTMLElement | null>(null)
const compactGroupMeasure = ref<HTMLElement | null>(null)
const scopeRoots = ref<MemoryScopeRoot[]>([])
const favoriteMemories = ref<Memory[]>([])
const sidebarError = ref('')
const compactGroupLinksWidth = ref(0)
const compactGroupOverflowWidth = ref(48)
const compactGroupItemWidths = ref<Record<string, number>>({})
let loadSequence = 0
let compactGroupResizeObserver: RafResizeObserver | null = null

const canCreateTenant = computed(() => authState.user?.serverRole === 'ServerOwner')
const activeTenantName = computed(() => authState.activeTenantName || authState.tenants[0]?.name || '')
const activeTenant = computed(() => authState.tenants.find((tenant) => tenant.name === activeTenantName.value))
const personalRoot = computed(() => scopeRoots.value.find((scopeRoot) => scopeRoot.kind === 'USER'))
const groupRoots = computed(() => scopeRoots.value.filter((scopeRoot) => scopeRoot.kind === 'GROUP'))
const brandLogoUrl = computed(() => (resolvedTheme.value === 'dark' ? logoDarkUrl : logoLightUrl))
const visibleCompactGroupRoots = computed(() => {
  const availableWidth = compactGroupLinksWidth.value - compactGroupOverflowWidth.value - 8
  if (availableWidth <= 0) return groupRoots.value.slice(0, 1)

  const visible: MemoryScopeRoot[] = []
  let usedWidth = 0
  for (const scopeRoot of groupRoots.value) {
    const itemWidth = compactGroupItemWidths.value[scopeRoot.path] ?? 126
    const nextWidth = usedWidth + itemWidth + (visible.length > 0 ? 8 : 0)
    if (nextWidth > availableWidth) break
    visible.push(scopeRoot)
    usedWidth = nextWidth
  }

  return visible.length > 0 ? visible : groupRoots.value.slice(0, 1)
})
const overflowCompactGroupRoots = computed(() => {
  const visiblePaths = new Set(visibleCompactGroupRoots.value.map((scopeRoot) => scopeRoot.path))
  return groupRoots.value.filter((scopeRoot) => !visiblePaths.has(scopeRoot.path))
})
const groupMenuItems = computed<MenuItem[]>(() => {
  const items: MenuItem[] = overflowCompactGroupRoots.value.map((scopeRoot) => ({
    label: scopeLabel(scopeRoot),
    icon: scopeIcon(scopeRoot),
    command: () => {
      void router.push(scopeRoot.path)
    }
  }))

  if (activeTenant.value) {
    if (items.length > 0) items.push({ separator: true })
    items.push({
      label: t('navigation.groupCreate'),
      icon: 'pi pi-plus',
      command: () => {
        emit('createGroup')
      }
    })
  }

  return items
})

watch(
  [
    activeTenantName,
    memoryFavoritesRefreshToken,
    () => authState.isAuthenticated,
    () => authState.tenants.length,
    () => props.navigationRefreshKey
  ],
  (current, previous) => {
    const preserveExisting =
      previous !== undefined &&
      current[0] === previous[0] &&
      current[2] === previous[2] &&
      current[3] === previous[3] &&
      current[4] === previous[4]
    void loadNavigationData({ preserveExisting })
  },
  { immediate: true }
)
watch(groupRoots, () => {
  void measureCompactGroupNav()
})

onMounted(() => {
  void measureCompactGroupNav()
  if (typeof ResizeObserver === 'undefined') return

  compactGroupResizeObserver = createRafResizeObserver(() => {
    void measureCompactGroupNav()
  })
  if (compactGroupLinks.value) compactGroupResizeObserver?.observe(compactGroupLinks.value)
  if (compactGroupMeasure.value) compactGroupResizeObserver?.observe(compactGroupMeasure.value)
})

onBeforeUnmount(() => {
  compactGroupResizeObserver?.disconnect()
  compactGroupResizeObserver = null
})

async function loadNavigationData(options: { preserveExisting?: boolean } = {}) {
  const tenantName = activeTenantName.value
  const sequence = ++loadSequence
  if (!options.preserveExisting) {
    scopeRoots.value = []
    favoriteMemories.value = []
  }
  sidebarError.value = ''
  if (!tenantName) return

  try {
    const navigation = await getMemoryNavigationContext(tenantName, '/')
    if (sequence !== loadSequence) return
    scopeRoots.value = navigation.scopeRoots
    favoriteMemories.value = navigation.favoriteMemories
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

function memoryCoverUrl(memory: Memory): string {
  return memory.coverUrl ? `${API_BASE}${memory.coverUrl}` : ''
}

function toggleGroupMenu(event: MouseEvent) {
  groupMenu.value?.toggle(event)
}

function closeMobileMenu() {
  mobileMenuOpen.value = false
}

function switchTenant(tenantName: string) {
  const tenant = authState.tenants.find((item) => item.name === tenantName)
  if (!tenant) return

  setActiveTenantName(tenantName)
  if (normalizedHost(tenant.domain) === normalizedHost(window.location.host)) {
    void router.push('/')
    return
  }

  window.location.assign(new URL('/', `${window.location.protocol}//${tenant.domain}`).toString())
}

function normalizedHost(value: string): string {
  return value.trim().toLowerCase().replace(/\.$/, '')
}

async function measureCompactGroupNav() {
  await nextTick()
  if (compactGroupLinks.value) {
    compactGroupLinksWidth.value = compactGroupLinks.value.getBoundingClientRect().width
  }

  const measureRoot = compactGroupMeasure.value
  if (!measureRoot) return

  const widths: Record<string, number> = {}
  for (const element of Array.from(measureRoot.querySelectorAll<HTMLElement>('[data-compact-group-path]'))) {
    const path = element.dataset.compactGroupPath
    if (path) widths[path] = Math.ceil(element.getBoundingClientRect().width)
  }
  compactGroupItemWidths.value = widths

  const overflowButton = measureRoot.querySelector<HTMLElement>('[data-compact-group-overflow]')
  if (overflowButton) {
    compactGroupOverflowWidth.value = Math.ceil(overflowButton.getBoundingClientRect().width)
  }
}
</script>

<template>
  <aside class="tenant-sidebar">
    <RouterLink to="/" class="sidebar-brand" aria-label="veyq Home">
      <img
        class="sidebar-brand-mark"
        :src="brandLogoUrl"
        alt=""
        aria-hidden="true"
      />
      <span>veyq</span>
    </RouterLink>

    <section class="sidebar-section sidebar-tenant-section">
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
      <div
        v-if="activeTenant"
        class="compact-menu-button compact-tenant-menu-button compact-tenant-current"
      >
        <span class="sidebar-tenant-avatar">
          <img v-if="activeTenant.logoUrl" :src="activeTenant.logoUrl" alt="" />
          <span v-else>{{ tenantInitial(activeTenant.displayName) }}</span>
        </span>
        <span class="compact-menu-label">{{ activeTenant.displayName }}</span>
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
            <span class="sidebar-tenant-avatar">
              <img v-if="tenant.logoUrl" :src="tenant.logoUrl" alt="" />
              <span v-else>{{ tenantInitial(tenant.displayName) }}</span>
            </span>
            <span class="sidebar-tenant-text">
              <strong>{{ tenant.displayName }}</strong>
              <small>{{ tenant.domain }}</small>
            </span>
          </div>
          <button
            v-else
            class="sidebar-tenant-link"
            :title="tenant.displayName"
            type="button"
            @click="switchTenant(tenant.name)"
          >
            <span class="sidebar-tenant-avatar">
              <img v-if="tenant.logoUrl" :src="tenant.logoUrl" alt="" />
              <span v-else>{{ tenantInitial(tenant.displayName) }}</span>
            </span>
            <span class="sidebar-tenant-text">
              <strong>{{ tenant.displayName }}</strong>
              <small>{{ tenant.domain }}</small>
            </span>
          </button>
        </div>
      </div>
    </section>

    <nav v-if="activeTenant" class="sidebar-nav" :aria-label="t('navigation.workspaceNavigation')">
      <RouterLink
        v-if="personalRoot"
        :to="personalRoot.path"
        class="sidebar-nav-link sidebar-personal-link"
      >
        <span class="pi pi-user" />
        <span>{{ t('navigation.personal') }}</span>
      </RouterLink>
    </nav>

    <section class="sidebar-section sidebar-favorites-section">
      <div class="sidebar-section-heading">
        <p class="sidebar-section-label">{{ t('navigation.favorites') }}</p>
      </div>
      <RouterLink
        v-for="memory in favoriteMemories"
        :key="memory.id"
        :to="memory.path"
        class="sidebar-scope-link sidebar-favorite-link"
      >
        <span class="sidebar-favorite-cover">
          <MemoryCoverImage
            v-if="memoryCoverUrl(memory)"
            :src="memoryCoverUrl(memory)"
            :alt="memory.displayName"
            :crop="memory.coverCrop"
          />
          <span v-else class="pi pi-images" />
        </span>
        <span>
          <strong>{{ memory.displayName }}</strong>
          <small>{{ memory.path }}</small>
        </span>
      </RouterLink>
    </section>

    <section v-if="activeTenant" class="sidebar-section sidebar-groups-section">
      <div class="sidebar-section-heading">
        <p class="sidebar-section-label">{{ t('navigation.groups') }}</p>
        <button
          v-if="activeTenant"
          class="sidebar-section-action"
          type="button"
          :title="t('navigation.groupCreate')"
          :aria-label="t('navigation.groupCreate')"
          @click="emit('createGroup')"
        >
          <span class="pi pi-plus" />
        </button>
      </div>
      <div class="compact-group-nav">
        <span class="compact-group-divider" aria-hidden="true" />
        <div ref="compactGroupLinks" class="compact-group-links">
          <RouterLink
            v-for="scopeRoot in visibleCompactGroupRoots"
            :key="scopeRoot.path"
            :to="scopeRoot.path"
            class="sidebar-scope-link compact-group-link"
          >
            <span :class="['sidebar-scope-icon', scopeIcon(scopeRoot)]" />
            <span>
              <strong>{{ scopeLabel(scopeRoot) }}</strong>
              <small>{{ scopeMeta(scopeRoot) }}</small>
            </span>
          </RouterLink>
          <button
            v-if="groupMenuItems.length"
            class="compact-group-overflow-button"
            type="button"
            :aria-label="t('navigation.groupsMenuOpen')"
            aria-haspopup="menu"
            @click="toggleGroupMenu"
          >
            <span class="pi pi-chevron-down" />
          </button>
        </div>
      </div>
      <div ref="compactGroupMeasure" class="compact-group-measure" aria-hidden="true">
        <span
          v-for="scopeRoot in groupRoots"
          :key="scopeRoot.path"
          class="sidebar-scope-link compact-group-link"
          :data-compact-group-path="scopeRoot.path"
        >
          <span :class="['sidebar-scope-icon', scopeIcon(scopeRoot)]" />
          <span>
            <strong>{{ scopeLabel(scopeRoot) }}</strong>
            <small>{{ scopeMeta(scopeRoot) }}</small>
          </span>
        </span>
        <button class="compact-group-overflow-button" type="button" data-compact-group-overflow>
          <span class="pi pi-chevron-down" />
        </button>
      </div>
      <RouterLink
        v-for="scopeRoot in groupRoots"
        :key="scopeRoot.path"
        :to="scopeRoot.path"
        class="sidebar-scope-link desktop-group-link"
      >
        <span :class="['sidebar-scope-icon', scopeIcon(scopeRoot)]" />
        <span>
          <strong>{{ scopeLabel(scopeRoot) }}</strong>
          <small>{{ scopeMeta(scopeRoot) }}</small>
        </span>
      </RouterLink>
      <Menu ref="groupMenu" :model="groupMenuItems" popup />
    </section>

    <section class="sidebar-section sidebar-profile">
      <ProfileMenu show-name />
    </section>

    <button
      class="mobile-navigation-button"
      type="button"
      :aria-label="t('navigation.mobileMenuOpen')"
      @click="mobileMenuOpen = true"
    >
      <span class="pi pi-bars" />
    </button>

    <Dialog
      v-model:visible="mobileMenuOpen"
      modal
      position="bottom"
      class="tenant-dialog mobile-nav-dialog"
      :header="t('navigation.mobileMenu')"
    >
      <div class="mobile-nav-sheet">
        <header v-if="activeTenant" class="mobile-nav-sheet-header">
          <span class="sidebar-tenant-avatar">
            <img v-if="activeTenant.logoUrl" :src="activeTenant.logoUrl" alt="" />
            <span v-else>{{ tenantInitial(activeTenant.displayName) }}</span>
          </span>
          <span>
            <strong>{{ activeTenant.displayName }}</strong>
            <small>{{ activeTenant.domain }}</small>
          </span>
        </header>

        <section class="mobile-nav-section">
          <h2>{{ t('tenant.title') }}</h2>
          <div class="mobile-nav-list">
            <button
              v-for="tenant in authState.tenants"
              :key="tenant.id"
              class="mobile-nav-item"
              :class="{ 'mobile-nav-item-active': activeTenantName === tenant.name }"
              type="button"
              @click="switchTenant(tenant.name); closeMobileMenu()"
            >
              <span class="sidebar-tenant-avatar">
                <img v-if="tenant.logoUrl" :src="tenant.logoUrl" alt="" />
                <span v-else>{{ tenantInitial(tenant.displayName) }}</span>
              </span>
              <span>
                <strong>{{ tenant.displayName }}</strong>
                <small>{{ tenant.domain }}</small>
              </span>
            </button>
            <button
              v-if="canCreateTenant"
              class="mobile-nav-item mobile-nav-action"
              type="button"
              @click="emit('createTenant'); closeMobileMenu()"
            >
              <span class="pi pi-plus" />
              <span>{{ t('navigation.createTenant') }}</span>
            </button>
          </div>
        </section>

        <section v-if="activeTenant" class="mobile-nav-section">
          <h2>{{ t('navigation.workspaceNavigation') }}</h2>
          <div class="mobile-nav-list">
            <RouterLink
              v-if="personalRoot"
              :to="personalRoot.path"
              class="mobile-nav-item"
              @click="closeMobileMenu"
            >
              <span class="sidebar-scope-icon pi pi-user" />
              <span>{{ t('navigation.personal') }}</span>
            </RouterLink>
          </div>
        </section>

        <section v-if="activeTenant" class="mobile-nav-section">
          <h2>{{ t('navigation.groups') }}</h2>
          <div class="mobile-nav-list">
            <RouterLink
              v-for="scopeRoot in groupRoots"
              :key="scopeRoot.path"
              :to="scopeRoot.path"
              class="mobile-nav-item"
              @click="closeMobileMenu"
            >
              <span :class="['sidebar-scope-icon', scopeIcon(scopeRoot)]" />
              <span>
                <strong>{{ scopeLabel(scopeRoot) }}</strong>
                <small>{{ scopeMeta(scopeRoot) }}</small>
              </span>
            </RouterLink>
            <button
              type="button"
              class="mobile-nav-item mobile-nav-action"
              @click="emit('createGroup'); closeMobileMenu()"
            >
              <span class="pi pi-plus" />
              <span>{{ t('navigation.groupCreate') }}</span>
            </button>
          </div>
        </section>

        <section class="mobile-nav-section">
          <h2>{{ t('navigation.favorites') }}</h2>
          <div class="mobile-nav-list">
            <RouterLink
              v-for="memory in favoriteMemories"
              :key="memory.id"
              :to="memory.path"
              class="mobile-nav-item mobile-nav-favorite"
              @click="closeMobileMenu"
            >
              <span class="sidebar-favorite-cover">
                <MemoryCoverImage
                  v-if="memoryCoverUrl(memory)"
                  :src="memoryCoverUrl(memory)"
                  :alt="memory.displayName"
                  :crop="memory.coverCrop"
                />
                <span v-else class="pi pi-images" />
              </span>
              <span>
                <strong>{{ memory.displayName }}</strong>
                <small>{{ memory.path }}</small>
              </span>
            </RouterLink>
            <p v-if="favoriteMemories.length === 0" class="mobile-nav-empty">
              {{ t('navigation.noFavorites') }}
            </p>
          </div>
        </section>
      </div>
    </Dialog>

    <p v-if="sidebarError" class="sidebar-error">{{ sidebarError }}</p>
  </aside>
</template>
