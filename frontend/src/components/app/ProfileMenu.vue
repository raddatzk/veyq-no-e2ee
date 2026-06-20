<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import Avatar from 'primevue/avatar'
import TieredMenu from 'primevue/tieredmenu'
import { logout } from '../../features/account/login/login'
import { clearRememberedLogin } from '../../features/account/rememberedLogin/rememberedLogin'
import { session, setUnlocked } from '../../app/session'
import { i18n, setLocale, t, type Locale } from '../../i18n/i18n'
import { useAuthState } from '../../shared/state/useAuthState'
import { useThemeState, type ThemePreference } from '../../shared/state/useThemeState'

const SUPPORTED_LOCALES: readonly Locale[] = ['de', 'en']
const userMenu = ref()
const router = useRouter()
const { authState } = useAuthState()
const { themePreference, setThemePreference } = useThemeState()
defineProps<{
  showName?: boolean
}>()
const profileLabel = computed(() => authState.userLabel.charAt(0).toUpperCase() || 'A')
const isServerOwner = computed(() => authState.user?.serverRole === 'ServerOwner')
const languageMenuItems = computed(() =>
  SUPPORTED_LOCALES.map((nextLocale) => ({
    label: t(`navigation.locales.${nextLocale}`),
    icon: i18n.locale === nextLocale ? 'pi pi-check' : 'pi pi-circle',
    command: () => setLocale(nextLocale)
  }))
)
const themeMenuItems = computed(() =>
  (['system', 'light', 'dark'] as ThemePreference[]).map((preference) => ({
    label: t(`navigation.themes.${preference}`),
    icon: themePreference.value === preference ? 'pi pi-check' : 'pi pi-circle',
    command: () => setThemePreference(preference)
  }))
)
const menuItems = computed(() => {
  if (!authState.isAuthenticated) {
    return [
      {
        label: t('auth.login'),
        icon: 'pi pi-sign-in',
        command: () => {
          void router.push('/login')
        }
      }
    ]
  }

  return [
    {
      label: authState.userLabel,
      icon: 'pi pi-user'
    },
    {
      separator: true
    },
    {
      label: t('profile.mySettings'),
      icon: 'pi pi-cog',
      command: () => {
        void router.push('/~me/settings')
      }
    },
    ...(isServerOwner.value
      ? [
          {
            label: t('settings.title'),
            icon: 'pi pi-server',
            command: () => {
              void router.push('/server/settings')
            }
          }
        ]
      : []),
    {
      label: t('navigation.language'),
      icon: 'pi pi-language',
      items: languageMenuItems.value
    },
    {
      label: t('navigation.theme'),
      icon: 'pi pi-palette',
      items: themeMenuItems.value
    },
    {
      separator: true
    },
    {
      label: t('auth.logout'),
      icon: 'pi pi-sign-out',
      command: () => {
        void doLogout()
      }
    }
  ]
})

async function doLogout() {
  const token = session.unlocked?.sessionToken
  if (token) {
    try {
      await logout(session.apiBase, token)
    } catch {
      // best-effort server-side invalidation; the local session is cleared regardless
    }
  }
  clearRememberedLogin()
  setUnlocked(null)
  await router.push('/login')
}
</script>

<template>
  <button class="profile-button" type="button" @click="userMenu.toggle($event)">
    <Avatar :label="profileLabel" shape="circle" />
    <span v-if="showName" class="profile-button-name">{{ authState.userLabel }}</span>
    <span v-if="showName" class="pi pi-chevron-down profile-button-chevron" />
  </button>
  <TieredMenu ref="userMenu" :model="menuItems" popup />
</template>
