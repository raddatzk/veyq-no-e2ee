<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import Avatar from 'primevue/avatar'
import TieredMenu from 'primevue/tieredmenu'
import { logout } from '../../api/authClient'
import { setLocale, SUPPORTED_LOCALES, type SupportedLocale } from '../../i18n'
import { useAuthState } from '../../shared/state/useAuthState'
import { useThemeState, type ThemePreference } from '../../shared/state/useThemeState'

const userMenu = ref()
const router = useRouter()
const { locale, t } = useI18n()
const { authState, setAnonymous } = useAuthState()
const { themePreference, setThemePreference } = useThemeState()
defineProps<{
  showName?: boolean
}>()
const profileLabel = computed(() => authState.userLabel.charAt(0).toUpperCase() || 'A')
const isServerOwner = computed(() => authState.user?.serverRole === 'ServerOwner')
const languageMenuItems = computed(() =>
  SUPPORTED_LOCALES.map((nextLocale) => ({
    label: t(`navigation.locales.${nextLocale}`),
    icon: locale.value === nextLocale ? 'pi pi-check' : 'pi pi-circle',
    command: () => setLocale(nextLocale as SupportedLocale)
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
        void router.push('/~me')
      }
    },
    ...(isServerOwner.value
      ? [
          {
            label: t('settings.title'),
            icon: 'pi pi-server',
            command: () => {
              void router.push('/settings')
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
  await logout()
  setAnonymous()
  await router.push('/')
}
</script>

<template>
  <button class="profile-button" type="button" @click="userMenu.toggle($event)">
    <Avatar v-if="authState.user?.avatarUrl" :image="authState.user.avatarUrl" shape="circle" />
    <Avatar v-else :label="profileLabel" shape="circle" />
    <span v-if="showName" class="profile-button-name">{{ authState.userLabel }}</span>
    <span v-if="showName" class="pi pi-chevron-down profile-button-chevron" />
  </button>
  <TieredMenu ref="userMenu" :model="menuItems" popup />
</template>
