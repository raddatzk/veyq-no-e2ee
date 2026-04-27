<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import Avatar from 'primevue/avatar'
import Menu from 'primevue/menu'
import { authState } from '../../authState'
import { logout } from '../../api/authClient'

const userMenu = ref()
const router = useRouter()
const profileLabel = computed(() => authState.userLabel.charAt(0).toUpperCase() || 'A')
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
  authState.isAuthenticated = false
  authState.userLabel = ''
  authState.user = null
  authState.tenants = []
  await router.push('/')
}
</script>

<template>
  <button class="profile-button" type="button" @click="userMenu.toggle($event)">
    <Avatar :label="profileLabel" shape="circle" />
  </button>
  <Menu ref="userMenu" :model="menuItems" popup />
</template>
