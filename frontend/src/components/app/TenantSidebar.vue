<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink } from 'vue-router'
import { authState } from '../../authState'
import ProfileMenu from './ProfileMenu.vue'

const emit = defineEmits<{
  createTenant: []
}>()
const canCreateTenant = computed(() => authState.user?.serverRole === 'SERVER_OWNER')
</script>

<template>
  <aside class="tenant-sidebar">
    <RouterLink to="/" class="brand-mark" title="Lunva">L</RouterLink>
    <RouterLink
      v-for="tenant in authState.tenants"
      :key="tenant.id"
      :to="`/${tenant.name}`"
      class="tenant-dot"
      :title="tenant.displayName"
    >
      {{ tenant.displayName.charAt(0).toUpperCase() }}
    </RouterLink>
    <button
      v-if="canCreateTenant"
      class="tenant-action"
      type="button"
      title="Tenant erstellen"
      @click="emit('createTenant')"
    >
      <i class="pi pi-plus"></i>
    </button>
    <ProfileMenu />
  </aside>
</template>
