<script setup lang="ts">
import { computed } from 'vue'
import { session } from '../session'
import { t } from '../../i18n/i18n'
import type { SpaceEntry } from '../../features/home/home'
import logoDarkUrl from '../../assets/brand/logo-dark.svg'
import logoLightUrl from '../../assets/brand/logo-light.svg'

const props = defineProps<{
  spaces: SpaceEntry[]
  favoriteMemories?: { id: string; name: string; path: string; cover?: string | null }[]
  activeScopeId?: string
  currentUserName?: string
}>()

const emit = defineEmits<{
  switchSpace: [entry: SpaceEntry]
  openFavorite: [id: string]
  createTenant: []
  createGroupSpace: []
  inviteTenant: [entry: SpaceEntry]
  manageGroup: [entry: SpaceEntry]
  openSettings: []
}>()

const tenantSpaces = computed(() => props.spaces.filter((s) => s.kind === 'TENANT'))
const groupSpaces = computed(() => props.spaces.filter((s) => s.kind === 'GROUP'))
const primarySpaces = computed(() => [
  ...tenantSpaces.value,
  ...props.spaces.filter((s) => s.kind === 'PERSONAL'),
])
const brandLogoUrl = computed(() => (session.theme === 'dark' ? logoDarkUrl : logoLightUrl))
const canCreateTenant = computed(() => session.unlocked?.user.serverRole === 'ServerOwner')

function spaceSub(s: SpaceEntry): string {
  if (s.kind === 'PERSONAL') return ''
  if (s.domain) return s.domain
  return s.role ?? t('home.group')
}

function spaceTitle(s: SpaceEntry): string {
  if (s.kind === 'PERSONAL') return t('home.personal')
  return s.displayName
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
          @click="emit('createTenant')"
        >+</button>
      </div>
      <div v-for="s in primarySpaces" :key="s.scopeId" class="space-row-wrap">
        <button
          class="space-row"
          :class="{ active: activeScopeId === s.scopeId }"
          @click="emit('switchSpace', s)"
        >
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
          @click.stop="emit('inviteTenant', s)"
        >⚙</button>
      </div>
    </section>

    <section v-if="favoriteMemories?.length" class="home-sidebar-section">
      <div class="home-section-row">
        <div class="home-section">{{ t('home.favorites') }}</div>
      </div>
      <button
        v-for="favorite in favoriteMemories"
        :key="favorite.id"
        class="favorite-row"
        type="button"
        @click="emit('openFavorite', favorite.id)"
      >
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

    <section class="home-sidebar-section">
      <div class="home-section-row">
        <div class="home-section">{{ t('home.groups') }}</div>
        <button
          v-if="tenantSpaces.length"
          class="home-section-action"
          type="button"
          :title="t('groups.create')"
          :aria-label="t('groups.create')"
          @click="emit('createGroupSpace')"
        >+</button>
      </div>
      <div v-for="s in groupSpaces" :key="s.scopeId" class="space-row-wrap">
        <button class="space-row" :class="{ active: activeScopeId === s.scopeId }" @click="emit('switchSpace', s)">
          <span class="space-avatar">
            <img v-if="safeLogoUrl(s.logoUrl)" :src="safeLogoUrl(s.logoUrl) || undefined" alt="" />
            <template v-else>{{ initial(s.displayName) }}</template>
          </span>
          <span class="space-meta">
            <div class="space-name">{{ s.displayName }}</div>
            <div class="space-sub">{{ spaceSub(s) }}</div>
          </span>
        </button>
        <button v-if="s.role === 'GroupOwner'" class="space-invite" :title="t('groups.members')" @click.stop="emit('manageGroup', s)">⚙</button>
      </div>
    </section>

    <div class="home-spacer" />
    <button class="space-row home-account-row" @click="emit('openSettings')">
      <span class="space-avatar home-account-avatar">{{ initial(currentUserName) }}</span>
      <span class="space-meta">
        <div class="space-name">{{ currentUserName }}</div>
      </span>
      <span class="home-account-chevron" aria-hidden="true"></span>
    </button>
  </aside>
</template>
