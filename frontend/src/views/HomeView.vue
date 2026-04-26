<script setup lang="ts">
import { RouterLink } from 'vue-router'
import { authState } from '../authState'
</script>

<template>
  <section class="card">
    <h1>Home</h1>
    <p v-if="authState.loading">Lade Sessionstatus...</p>
    <p v-else-if="authState.isAuthenticated">Eingeloggt als {{ authState.username }}.</p>
    <p v-else>
      Du bist nicht eingeloggt.
      <RouterLink to="/login">Zur Loginseite</RouterLink>
    </p>
  </section>
</template>
