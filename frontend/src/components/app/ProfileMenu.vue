<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import Avatar from 'primevue/avatar'
import Menu from 'primevue/menu'
import { logout } from '../../api/authClient'
import { useAuthState } from '../../shared/state/useAuthState'

const userMenu = ref()
const router = useRouter()
const { authState, setAnonymous } = useAuthState()
const profileLabel = computed(() => authState.userLabel.charAt(0).toUpperCase() || 'A')
const isServerOwner = computed(() => authState.user?.serverRole === 'ServerOwner')
const menuItems = computed(() => {
  if (!authState.isAuthenticated) {
    return [
      {
        label: 'Login',
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
      label: 'Meine Einstellungen',
      icon: 'pi pi-cog',
      command: () => {
        void router.push('/~me')
      }
    },
    ...(isServerOwner.value
      ? [
          {
            label: 'Server Einstellungen',
            icon: 'pi pi-server',
            command: () => {
              void router.push('/settings')
            }
          }
        ]
      : []),
    {
      separator: true
    },
    {
      label: 'Logout',
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
    <Avatar :label="profileLabel" shape="circle" />
  </button>
  <Menu ref="userMenu" :model="menuItems" popup />
</template>
