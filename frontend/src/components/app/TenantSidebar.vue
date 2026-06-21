<script setup lang="ts">
// The workspace sidebar, ported from the pre-promotion rich client (app/home/HomeSidebar) onto the current
// E2EE data layer: spaces split into "Bereich" (personal + tenants) and "Gruppen", favourites of the active
// space as quick links, and create/manage affordances that route to the settings view (where the admin
// slice lives). Styling comes from the ported styles/home.css (.home-sidebar, .space-row, .favorite-row …).
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import logoDarkUrl from '../../assets/brand/logo-dark.svg'
import logoLightUrl from '../../assets/brand/logo-light.svg'
import { useThemeState } from '../../shared/state/useThemeState'
import { ensureSpaces, spaces } from '../../app/spaces'
import { session } from '../../app/session'
import { buildHomePath } from '../../features/home/homeRoute'
import { ancestorChain, loadCover } from '../../features/home/home'
import { listFavoriteNodeIds } from '../../features/memory/favorite/favorite'
import type { MemoryNode } from '../../features/memory/createTree/memoryTree'
import type { SpaceEntry } from '../../features/spaces/switchActiveSpace/switchActiveSpace'
import { t } from '../../i18n/i18n'

const router = useRouter()
const { resolvedTheme } = useThemeState()
const error = ref('')
const brandLogoUrl = computed(() => (resolvedTheme.value === 'dark' ? logoDarkUrl : logoLightUrl))
const activeScopeId = computed(() => session.activeSpace?.scope.scopeId ?? '')
const currentUserName = computed(() => session.unlocked?.user.displayName ?? '')

const tenantSpaces = computed(() => spaces.value.filter((s) => s.kind === 'TENANT'))
const groupSpaces = computed(() => spaces.value.filter((s) => s.kind === 'GROUP'))
const primarySpaces = computed(() => [...tenantSpaces.value, ...spaces.value.filter((s) => s.kind === 'PERSONAL')])
const canCreateTenant = computed(() => session.unlocked?.user.serverRole === 'ServerOwner')

type Favorite = { id: string; name: string; path: string; href: string; cover?: string | null }
const favorites = ref<Favorite[]>([])
let coverUrls: string[] = []

function spaceSub(s: SpaceEntry): string {
  if (s.kind === 'PERSONAL') return ''
  if (s.domain) return s.domain
  return s.role ?? t('home.group')
}
function spaceTitle(s: SpaceEntry): string {
  return s.kind === 'PERSONAL' ? t('home.personal') : s.displayName
}
function initial(value?: string): string {
  return (value || '?').charAt(0).toUpperCase()
}
function safeLogoUrl(value?: string): string | null {
  const trimmed = value?.trim()
  if (!trimmed) return null
  if (trimmed.startsWith('/') && !trimmed.startsWith('//')) return trimmed
  try {
    const url = new URL(trimmed)
    return url.protocol === 'https:' || url.protocol === 'http:' ? trimmed : null
  } catch {
    return null
  }
}

function openSpace(entry: SpaceEntry) {
  void router.push(buildHomePath(entry, []))
}
function openFavorite(href: string) {
  void router.push(href)
}
// Create/manage flows live in the settings view (admin slice); the sidebar just routes there.
function openSettings() {
  void router.push('/~me/settings')
}

function releaseCovers() {
  coverUrls.forEach((u) => URL.revokeObjectURL(u))
  coverUrls = []
}
async function loadFavorites() {
  releaseCovers()
  favorites.value = []
  const active = session.activeSpace
  if (!active) return
  try {
    const ids = await listFavoriteNodeIds(session.apiBase, session.unlocked!, active.scope.scopeId)
    const built = await Promise.all(
      ids.map(async (id) => {
        const chain = await ancestorChain(session.apiBase, session.unlocked!, active, id)
        const leaf = chain.at(-1)
        if (!leaf) return null
        return {
          id,
          name: leaf.name,
          path: chain.slice(0, -1).map((c) => c.name).join(' / '),
          href: buildHomePath(active.entry, chain.map((c) => c.slug)),
          node: leaf.node as MemoryNode,
        }
      }),
    )
    const resolved = built.filter((f): f is NonNullable<typeof f> => f !== null)
    favorites.value = resolved.map((f) => ({ id: f.id, name: f.name, path: f.path, href: f.href, cover: null }))
    for (const f of resolved) {
      void loadCover(session.apiBase, session.unlocked!, active, f.node).then((url) => {
        if (!url) return
        coverUrls.push(url)
        favorites.value = favorites.value.map((x) => (x.id === f.id ? { ...x, cover: url } : x))
      })
    }
  } catch {
    // Favourites are a convenience; a failure here must not break the sidebar.
  }
}

onMounted(async () => {
  try {
    await ensureSpaces()
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  }
})
watch(activeScopeId, loadFavorites, { immediate: true })
onBeforeUnmount(releaseCovers)
</script>

<template>
  <aside class="home-sidebar">
    <div class="home-brand">
      <img class="home-brand-mark" :src="brandLogoUrl" alt="" aria-hidden="true" />
      <span>veyq</span>
    </div>

    <section class="home-sidebar-section">
      <div class="home-section-row">
        <div class="home-section">{{ t('home.spaces') }}</div>
        <button
          v-if="canCreateTenant"
          class="home-section-action"
          type="button"
          :title="t('tenants.create')"
          :aria-label="t('tenants.create')"
          @click="openSettings"
        >+</button>
      </div>
      <div v-for="s in primarySpaces" :key="s.scopeId" class="space-row-wrap">
        <button class="space-row" :class="{ active: activeScopeId === s.scopeId }" @click="openSpace(s)">
          <span class="space-avatar" :class="{ 'space-avatar-personal': s.kind === 'PERSONAL' }">
            <span v-if="s.kind === 'PERSONAL'" class="space-person-icon" aria-hidden="true"></span>
            <img v-else-if="safeLogoUrl(s.logoUrl)" :src="safeLogoUrl(s.logoUrl) || undefined" alt="" />
            <template v-else>{{ initial(s.displayName) }}</template>
          </span>
          <span class="space-meta">
            <div class="space-name">{{ spaceTitle(s) }}</div>
            <div v-if="spaceSub(s)" class="space-sub">{{ spaceSub(s) }}</div>
          </span>
        </button>
        <button
          v-if="s.kind === 'TENANT' && s.role === 'Owner'"
          class="space-invite"
          type="button"
          :title="t('tenants.manageTitle')"
          :aria-label="t('tenants.manageTitle')"
          @click.stop="openSettings"
        >⚙</button>
      </div>
      <p v-if="error" class="sidebar-error">{{ error }}</p>
    </section>

    <section v-if="favorites.length" class="home-sidebar-section">
      <div class="home-section-row">
        <div class="home-section">{{ t('home.favorites') }}</div>
      </div>
      <button v-for="favorite in favorites" :key="favorite.id" class="favorite-row" type="button" @click="openFavorite(favorite.href)">
        <span class="favorite-cover">
          <img v-if="favorite.cover" :src="favorite.cover" alt="" />
          <span v-else aria-hidden="true">★</span>
        </span>
        <span class="favorite-meta">
          <strong>{{ favorite.name }}</strong>
          <small>{{ favorite.path }}</small>
        </span>
      </button>
    </section>

    <section v-if="groupSpaces.length || tenantSpaces.length" class="home-sidebar-section">
      <div class="home-section-row">
        <div class="home-section">{{ t('home.groups') }}</div>
        <button
          v-if="tenantSpaces.length"
          class="home-section-action"
          type="button"
          :title="t('groups.create')"
          :aria-label="t('groups.create')"
          @click="openSettings"
        >+</button>
      </div>
      <div v-for="s in groupSpaces" :key="s.scopeId" class="space-row-wrap">
        <button class="space-row" :class="{ active: activeScopeId === s.scopeId }" @click="openSpace(s)">
          <span class="space-avatar">
            <img v-if="safeLogoUrl(s.logoUrl)" :src="safeLogoUrl(s.logoUrl) || undefined" alt="" />
            <template v-else>{{ initial(s.displayName) }}</template>
          </span>
          <span class="space-meta">
            <div class="space-name">{{ s.displayName }}</div>
            <div class="space-sub">{{ spaceSub(s) }}</div>
          </span>
        </button>
        <button v-if="s.role === 'GroupOwner'" class="space-invite" type="button" :title="t('groups.members')" @click.stop="openSettings">⚙</button>
      </div>
    </section>

    <div class="home-spacer" />
    <button class="space-row home-account-row" type="button" @click="openSettings">
      <span class="space-avatar home-account-avatar">{{ initial(currentUserName) }}</span>
      <span class="space-meta">
        <div class="space-name">{{ currentUserName }}</div>
      </span>
      <span class="home-account-chevron" aria-hidden="true"></span>
    </button>
  </aside>
</template>
