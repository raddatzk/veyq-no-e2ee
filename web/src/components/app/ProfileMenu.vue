<script setup lang="ts">
// The sidebar profile (cr-0031). Markup + classes are the design source's ProfileMenu verbatim (.profile-button
// + a popup TieredMenu) so layout.css styles it identically; only the data + actions are ours — the label is
// the in-memory account email, and the menu carries language + appearance (theme) switching and sign-out.
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import Avatar from 'primevue/avatar'
import TieredMenu from 'primevue/tieredmenu'
import type { MenuItem } from 'primevue/menuitem'
import { account } from '../../app/account'
import { logout } from '../../features/account/login/login'
import { clearActiveSpace } from '../../app/activeSpace'
import { setLocale, SUPPORTED_LOCALES, LOCALE_LABELS, type SupportedLocale } from '../../i18n'
import { useThemeState, setThemePreference, type ThemePreference } from '../../shared/state/useThemeState'

defineProps<{ showName?: boolean }>()

const router = useRouter()
const { locale, t } = useI18n()
const { themePreference } = useThemeState()

const userLabel = computed(() => account.email || 'veyq')
const profileLabel = computed(() => userLabel.value.charAt(0).toUpperCase() || 'V')

const userMenu = ref<InstanceType<typeof TieredMenu> | null>(null)

const languageMenuItems = computed<MenuItem[]>(() =>
  SUPPORTED_LOCALES.map((next) => ({
    label: LOCALE_LABELS[next],
    icon: locale.value === next ? 'pi pi-check' : 'pi pi-circle',
    command: () => setLocale(next as SupportedLocale),
  })),
)
const themeMenuItems = computed<MenuItem[]>(() =>
  (['system', 'light', 'dark'] as ThemePreference[]).map((preference) => ({
    label: t(`shell.theme${preference.charAt(0).toUpperCase()}${preference.slice(1)}`),
    icon: themePreference.value === preference ? 'pi pi-check' : 'pi pi-circle',
    command: () => setThemePreference(preference),
  })),
)
const menuItems = computed<MenuItem[]>(() => [
  { label: userLabel.value, icon: 'pi pi-user' },
  { separator: true },
  { label: t('shell.language'), icon: 'pi pi-language', items: languageMenuItems.value },
  { label: t('shell.theme'), icon: 'pi pi-palette', items: themeMenuItems.value },
  { separator: true },
  { label: t('shell.logout'), icon: 'pi pi-sign-out', command: () => void signOut() },
])

async function signOut() {
  try {
    await logout()
  } finally {
    clearActiveSpace()
    await router.push({ name: 'login' })
  }
}
</script>

<template>
  <button class="profile-button" type="button" @click="userMenu?.toggle($event)">
    <Avatar :label="profileLabel" shape="circle" />
    <span v-if="showName" class="profile-button-name">{{ userLabel }}</span>
    <span v-if="showName" class="pi pi-chevron-down profile-button-chevron" />
  </button>
  <TieredMenu ref="userMenu" :model="menuItems" popup />
</template>
