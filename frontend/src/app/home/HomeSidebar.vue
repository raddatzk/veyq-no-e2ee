<script setup lang="ts">
import { computed } from 'vue'
import { session } from '../session'
import { t } from '../../i18n/i18n'
import type { SpaceEntry } from '../../features/home/home'
import logoDarkUrl from '../../assets/brand/logo-dark.svg'
import logoLightUrl from '../../assets/brand/logo-light.svg'

const props = defineProps<{
  spaces: SpaceEntry[]
  activeScopeId?: string
  currentUserName?: string
}>()

const emit = defineEmits<{
  switchSpace: [entry: SpaceEntry]
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

function spaceSub(s: SpaceEntry): string {
  if (s.kind === 'PERSONAL') return t('home.personal')
  if (s.domain) return s.domain
  return s.role ?? t('home.group')
}

function initial(value?: string): string {
  return (value || '?').charAt(0).toUpperCase()
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
        <button class="home-section-action" type="button" :title="t('tenants.create')" :aria-label="t('tenants.create')" @click="emit('createTenant')">+</button>
      </div>
      <button
        v-for="s in primarySpaces"
        :key="s.scopeId"
        class="space-row"
        :class="{ active: activeScopeId === s.scopeId }"
        @click="emit('switchSpace', s)"
      >
        <span class="space-avatar">{{ initial(s.displayName) }}</span>
        <span class="space-meta">
          <div class="space-name">{{ s.displayName }}</div>
          <div class="space-sub">{{ spaceSub(s) }}</div>
        </span>
      </button>
    </section>

    <section class="home-sidebar-section">
      <div class="home-section-row">
        <div class="home-section">{{ t('home.favorites') }}</div>
      </div>
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
          <span class="space-avatar">{{ initial(s.displayName) }}</span>
          <span class="space-meta">
            <div class="space-name">{{ s.displayName }}</div>
            <div class="space-sub">{{ spaceSub(s) }}</div>
          </span>
        </button>
        <button v-if="s.role === 'GroupOwner'" class="space-invite" :title="t('groups.members')" @click.stop="emit('manageGroup', s)">⚙</button>
      </div>
    </section>

    <div class="home-spacer" />
    <button class="space-row" @click="emit('openSettings')">
      <span class="space-avatar">{{ initial(currentUserName) }}</span>
      <span class="space-meta">
        <div class="space-name">{{ currentUserName }}</div>
        <div class="space-sub">{{ t('home.settings') }}</div>
      </span>
    </button>
  </aside>
</template>
