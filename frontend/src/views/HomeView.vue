<script setup lang="ts">
import { RouterLink } from 'vue-router'
import Card from 'primevue/card'
import Message from 'primevue/message'
import { authState } from '../authState'

function roleLabel(role: string) {
  return role === 'TenantOwner' ? 'Owner' : 'Member'
}
</script>

<template>
  <Card>
    <template #title>Home</template>
    <template #content>
    <Message v-if="authState.loading" severity="info" :closable="false">Lade Sessionstatus...</Message>
    <template v-else-if="authState.isAuthenticated">
      <p>Eingeloggt als {{ authState.userLabel }}.</p>
      <div v-if="authState.tenants.length" class="tenant-list">
        <RouterLink
          v-for="tenant in authState.tenants"
          :key="tenant.id"
          :to="`/${tenant.name}`"
          class="tenant-row"
        >
          <span>{{ tenant.displayName }}</span>
          <small>{{ roleLabel(tenant.role) }}</small>
        </RouterLink>
      </div>
    </template>
    <p v-else>
      Du bist nicht eingeloggt.
      <RouterLink to="/login">Zur Loginseite</RouterLink>
    </p>
    </template>
  </Card>
</template>
