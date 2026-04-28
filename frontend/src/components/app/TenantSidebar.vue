<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { useAuthState } from '../../shared/state/useAuthState'
import ProfileMenu from './ProfileMenu.vue'

const route = useRoute()
const { authState } = useAuthState()
const emit = defineEmits<{
  createTenant: []
}>()
const canCreateTenant = computed(() => authState.user?.serverRole === 'ServerOwner')
const activeTenantName = computed(() => String(route.params.tenantName || ''))
</script>

<template>
  <aside class="tenant-sidebar">
    <RouterLink to="/" class="brand-mark" title="Lunva">L</RouterLink>
    <RouterLink
      v-for="tenant in authState.tenants"
      :key="tenant.id"
      :to="`/${tenant.name}`"
      :class="['tenant-dot', { 'tenant-dot-active': activeTenantName === tenant.name }]"
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
