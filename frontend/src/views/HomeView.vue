<script setup lang="ts">
import { RouterLink } from 'vue-router'
import { authState } from '../authState'
</script>

<template>
  <section class="card">
    <h1>Home</h1>
    <p v-if="authState.loading">Lade Sessionstatus...</p>
    <template v-else-if="authState.isAuthenticated">
      <p>Eingeloggt als {{ authState.userLabel }}.</p>
      <div v-if="authState.tenants.length" class="tenant-list">
        <RouterLink
          v-for="tenant in authState.tenants"
          :key="tenant.id"
          :to="`/${tenant.slug}`"
          class="tenant-row"
        >
          <span>{{ tenant.name }}</span>
          <small>{{ tenant.role }}</small>
        </RouterLink>
      </div>
    </template>
    <p v-else>
      Du bist nicht eingeloggt.
      <RouterLink to="/login">Zur Loginseite</RouterLink>
    </p>
  </section>
</template>
