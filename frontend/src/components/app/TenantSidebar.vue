<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import logoDarkUrl from '../../assets/brand/logo-dark.svg'
import logoLightUrl from '../../assets/brand/logo-light.svg'
import { useThemeState } from '../../shared/state/useThemeState'
import { ensureSpaces, spaces } from '../../app/spaces'
import { session } from '../../app/session'
import { buildHomePath } from '../../features/home/homeRoute'
import type { SpaceEntry } from '../../features/spaces/switchActiveSpace/switchActiveSpace'
import { t } from '../../i18n/i18n'
import ProfileMenu from './ProfileMenu.vue'

// AppShell still binds these; the create flows are rebuilt with the admin slice (Phase 9).
defineProps<{ navigationRefreshKey?: number }>()
defineEmits<{ createTenant: []; createGroup: [] }>()

const router = useRouter()
const { resolvedTheme } = useThemeState()
const error = ref('')
const brandLogoUrl = computed(() => (resolvedTheme.value === 'dark' ? logoDarkUrl : logoLightUrl))
const activeScopeId = computed(() => session.activeSpace?.scope.scopeId ?? '')

function spaceIcon(entry: SpaceEntry): string {
  if (entry.kind === 'GROUP') return 'pi pi-users'
  if (entry.kind === 'TENANT') return 'pi pi-building'
  return 'pi pi-user'
}

function spaceMeta(entry: SpaceEntry): string {
  if (entry.kind === 'PERSONAL') return t('home.personal')
  if (entry.kind === 'GROUP') return t('home.group')
  return entry.domain ?? ''
}

function openSpace(entry: SpaceEntry) {
  void router.push(buildHomePath(entry, []))
}

onMounted(async () => {
  try {
    await ensureSpaces()
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  }
})
</script>

<template>
  <aside class="tenant-sidebar">
    <RouterLink to="/" class="sidebar-brand" aria-label="veyq Home">
      <img class="sidebar-brand-mark" :src="brandLogoUrl" alt="" aria-hidden="true" />
      <span>veyq</span>
    </RouterLink>

    <section class="sidebar-section">
      <div class="sidebar-section-heading">
        <p class="sidebar-section-label">{{ t('home.spaces') }}</p>
      </div>
      <button
        v-for="entry in spaces"
        :key="entry.scopeId"
        type="button"
        class="sidebar-scope-link"
        :class="{ 'sidebar-scope-link-active': entry.scopeId === activeScopeId }"
        @click="openSpace(entry)"
      >
        <span :class="['sidebar-scope-icon', spaceIcon(entry)]" />
        <span>
          <strong>{{ entry.displayName }}</strong>
          <small>{{ spaceMeta(entry) }}</small>
        </span>
      </button>
      <p v-if="error" class="sidebar-error">{{ error }}</p>
    </section>

    <section class="sidebar-section sidebar-profile">
      <ProfileMenu show-name />
    </section>
  </aside>
</template>
