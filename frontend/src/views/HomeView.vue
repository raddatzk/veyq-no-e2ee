<script setup lang="ts">
import { RouterLink } from 'vue-router'
import Card from 'primevue/card'
import Message from 'primevue/message'
import { useAuthState } from '../shared/state/useAuthState'

const { authState } = useAuthState()

function roleLabel(role: string) {
  return role === 'TenantOwner' ? 'Owner' : 'Member'
}
</script>

<template>
  <Card>
    <template #content>
      Currently, you don't have any tenants
µ    </template>
  </Card>
</template>
