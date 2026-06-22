<script setup lang="ts">
// Workspace sidebar wired to the E2EE backend: the personal space, the group spaces the user can enter, and
// the active space's favourite memories. Space/group names are decrypted client-side (app/spaces.loadSpaces);
// entering a space opens its scope + content key. Replaces the legacy tenant/permalink-driven navigation.
import { computed, onMounted, ref, watch } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import logoDarkUrl from '../../assets/brand/logo-dark.svg'
import logoLightUrl from '../../assets/brand/logo-light.svg'
import { useThemeState } from '../../shared/state/useThemeState'
import { ensureSpaces, invalidateSpaces } from '../../app/spaces'
import { enterSpace, loadMemoryById, type LevelEntry, type SpaceEntry } from '../../features/home/home'
import { listFavoriteNodeIds } from '../../features/memory/favorite/favorite'
import { requireSession, session, setActiveSpace } from '../../app/session'
import NodeCover from '../NodeCover.vue'
import ProfileMenu from './ProfileMenu.vue'

const emit = defineEmits<{ createTenant: []; createGroup: [] }>()

const router = useRouter()
const { t } = useI18n()
const { resolvedTheme } = useThemeState()

const spaces = ref<SpaceEntry[]>([])
const favorites = ref<LevelEntry[]>([])
const sidebarError = ref('')

const brandLogoUrl = computed(() => (resolvedTheme.value === 'dark' ? logoDarkUrl : logoLightUrl))
const canCreateGroup = computed(() => Boolean(session.unlocked))
const personalSpace = computed(() => spaces.value.find((s) => s.kind === 'PERSONAL'))
const groupSpaces = computed(() => spaces.value.filter((s) => s.kind === 'GROUP'))
const activeScopeId = computed(() => session.activeSpace?.scope.scopeId ?? null)

onMounted(load)
// Reload favourites whenever the active space changes (each scope has its own favourites).
watch(activeScopeId, loadFavorites)

async function load() {
  try {
    spaces.value = await ensureSpaces()
  } catch (caught) {
    sidebarError.value = caught instanceof Error ? caught.message : t('settings.loadFailed')
  }
  await loadFavorites()
}

async function loadFavorites() {
  const active = session.activeSpace
  if (!active) { favorites.value = []; return }
  try {
    const ids = await listFavoriteNodeIds(session.apiBase, requireSession(), active.scope.scopeId)
    const entries = await Promise.all(ids.map((id) => loadMemoryById(session.apiBase, requireSession(), active, id)))
    favorites.value = entries.filter((e): e is LevelEntry => e !== null)
  } catch {
    favorites.value = []
  }
}

function isActive(entry: SpaceEntry): boolean {
  return entry.scopeId === activeScopeId.value
}

async function open(entry: SpaceEntry) {
  if (isActive(entry)) { void router.push('/'); return }
  try {
    setActiveSpace(await enterSpace(session.apiBase, requireSession(), entry))
    invalidateSpaces()
    await router.push('/')
  } catch (caught) {
    sidebarError.value = caught instanceof Error ? caught.message : t('settings.loadFailed')
  }
}

function initial(name: string): string {
  return (name.trim()[0] ?? '?').toUpperCase()
}
</script>

<template>
  <aside class="tenant-sidebar">
    <RouterLink to="/" class="sidebar-brand" aria-label="veyq Home">
      <img class="sidebar-brand-mark" :src="brandLogoUrl" alt="" aria-hidden="true" />
      <span>veyq</span>
    </RouterLink>

    <!-- Personal space -->
    <nav class="sidebar-nav" :aria-label="t('navigation.workspaceNavigation')">
      <button
        v-if="personalSpace"
        type="button"
        class="sidebar-nav-link sidebar-personal-link"
        :class="{ 'sidebar-tenant-link-active': isActive(personalSpace) }"
        @click="open(personalSpace)"
      >
        <span class="pi pi-user" />
        <span>{{ t('navigation.personal') }}</span>
      </button>
    </nav>

    <!-- Groups -->
    <section class="sidebar-section sidebar-groups-section">
      <div class="sidebar-section-heading">
        <p class="sidebar-section-label">{{ t('navigation.groups') }}</p>
        <button
          v-if="canCreateGroup"
          class="sidebar-section-action"
          type="button"
          :title="t('navigation.groupCreate')"
          :aria-label="t('navigation.groupCreate')"
          @click="emit('createGroup')"
        >
          <span class="pi pi-plus" />
        </button>
      </div>
      <button
        v-for="group in groupSpaces"
        :key="group.scopeId"
        type="button"
        class="sidebar-scope-link"
        :class="{ 'sidebar-tenant-link-active': isActive(group) }"
        @click="open(group)"
      >
        <span class="sidebar-tenant-avatar"><span>{{ initial(group.displayName) }}</span></span>
        <span><strong>{{ group.displayName }}</strong></span>
      </button>
      <p v-if="groupSpaces.length === 0" class="sidebar-section-empty">{{ t('navigation.noGroups') }}</p>
    </section>

    <!-- Favourites of the active space -->
    <section class="sidebar-section sidebar-favorites-section">
      <div class="sidebar-section-heading">
        <p class="sidebar-section-label">{{ t('navigation.favorites') }}</p>
      </div>
      <RouterLink
        v-for="fav in favorites"
        :key="fav.node.id"
        :to="`/m/${fav.node.id}`"
        class="sidebar-scope-link sidebar-favorite-link"
      >
        <NodeCover v-if="session.activeSpace" :active="session.activeSpace" :node="fav.node" class="sidebar-favorite-cover" />
        <span><strong>{{ fav.name }}</strong></span>
      </RouterLink>
      <p v-if="favorites.length === 0" class="sidebar-section-empty">{{ t('navigation.noFavorites') }}</p>
    </section>

    <section class="sidebar-section sidebar-profile">
      <ProfileMenu show-name />
    </section>

    <p v-if="sidebarError" class="sidebar-error">{{ sidebarError }}</p>
  </aside>
</template>

<style scoped>
.sidebar-section-empty {
  font-size: 0.85rem;
  opacity: 0.5;
  margin: 0.25rem 0 0;
}
.sidebar-personal-link,
.sidebar-scope-link {
  width: 100%;
  background: transparent;
  border: none;
  color: inherit;
  cursor: pointer;
  text-align: left;
}
.sidebar-tenant-link-active {
  font-weight: 600;
  color: var(--p-primary-color, #5b4df5);
}
</style>
