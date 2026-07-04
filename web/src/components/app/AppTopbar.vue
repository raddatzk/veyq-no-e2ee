<script setup lang="ts">
// The shell's top bar (cr-0030). Markup + classes are the design source's WorkspaceTopbar verbatim (the
// .workspace-topbar grid: breadcrumb + a search center + actions) so layout.css styles it identically. Ours
// only differs in the data: the breadcrumb is built CLIENT-SIDE from the local decrypted tree (the blind
// server computes none), and global search is a later slice, so the center renders in its disabled state.
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import Breadcrumb from 'primevue/breadcrumb'
import type { MenuItem } from 'primevue/menuitem'
import { breadcrumb } from '../../app/breadcrumb'
import UploadStatusMenu from '../../features/memory/components/UploadStatusMenu.vue'
import { useThemeState, setThemePreference, type ThemePreference } from '../../shared/state/useThemeState'

const { t } = useI18n()
const router = useRouter()

// Theme is a frequent one-tap quick toggle → it lives here in the top bar (cr-0078 follow-up); language, which
// grows more options, stays in the profile menu + the settings hub, not here. Cycles system → light → dark.
const { themePreference } = useThemeState()
const THEME_CYCLE: ThemePreference[] = ['system', 'light', 'dark']
const themeIcon = computed(
  () => ({ system: 'pi pi-desktop', light: 'pi pi-sun', dark: 'pi pi-moon' })[themePreference.value],
)
const cycleTheme = () =>
  setThemePreference(THEME_CYCLE[(THEME_CYCLE.indexOf(themePreference.value) + 1) % THEME_CYCLE.length])

const home = computed<MenuItem>(() => ({
  label: breadcrumb.trail[0]?.name,
  command: () => void router.push({ hash: breadcrumb.trail[0]?.hash ?? '#~me' }),
}))
const items = computed<MenuItem[]>(() =>
  breadcrumb.trail.slice(1).map((crumb) => ({
    label: crumb.name,
    command: () => void router.push({ hash: crumb.hash }),
  })),
)
</script>

<template>
  <header class="workspace-topbar">
    <Breadcrumb :model="items" :home="home" class="app-breadcrumb" />
    <div class="workspace-search">
      <label class="workspace-topbar-center workspace-topbar-center-disabled" :aria-label="t('shell.search')">
        <span class="pi pi-search" />
        <input type="search" disabled :placeholder="t('shell.search')" autocomplete="off" />
        <kbd>⌘K</kbd>
      </label>
    </div>
    <div class="workspace-topbar-actions">
      <button
        type="button"
        class="topbar-icon-button"
        :aria-label="t('shell.theme')"
        :title="t('shell.theme')"
        @click="cycleTheme"
      >
        <span :class="themeIcon" />
      </button>
      <UploadStatusMenu />
      <button
        type="button"
        class="topbar-icon-button"
        :aria-label="t('settings.title')"
        :title="t('settings.title')"
        @click="router.push('/settings')"
      >
        <span class="pi pi-cog" />
      </button>
    </div>
  </header>
</template>

<style scoped>
.topbar-icon-button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border: none;
  border-radius: 0.5rem;
  background: transparent;
  color: inherit;
  cursor: pointer;
}
.topbar-icon-button:hover {
  background: var(--p-content-hover-background, rgba(127, 127, 127, 0.08));
}
</style>
