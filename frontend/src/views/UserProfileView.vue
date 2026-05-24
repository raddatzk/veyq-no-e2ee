<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import Message from 'primevue/message'
import ProgressSpinner from 'primevue/progressspinner'
import { getUserProfile, type UserProfile } from '../features/profiles/api'
import { routePathToMemoryPath } from '../shared/utils/paths'

const route = useRoute()
const tenantName = computed(() => String(route.params.tenantName || ''))
const profilePermalink = computed(() => {
  const firstSegment = routePathToMemoryPath(route.params.pathMatch).split('/').filter(Boolean)[0] || ''
  return firstSegment.startsWith('~') ? firstSegment.slice(1) : ''
})
const profile = ref<UserProfile | null>(null)
const loading = ref(false)
const error = ref('')

watch([tenantName, profilePermalink], loadProfile, { immediate: true })

async function loadProfile() {
  if (!tenantName.value || !profilePermalink.value) return
  loading.value = true
  error.value = ''
  try {
    profile.value = await getUserProfile(tenantName.value, profilePermalink.value)
  } catch (caught) {
    profile.value = null
    error.value = caught instanceof Error ? caught.message : 'Profil konnte nicht geladen werden.'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <main class="profile-page">
    <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>
    <div v-if="loading" class="memory-loading">
      <ProgressSpinner class="small-spinner" />
      <span>Profil wird geladen...</span>
    </div>
    <section v-else-if="profile" class="profile-header">
      <div class="profile-avatar" aria-hidden="true">{{ profile.initials }}</div>
      <div>
        <h1>{{ profile.displayName }}</h1>
        <p>~{{ profile.permalink }}</p>
      </div>
    </section>
  </main>
</template>

<style scoped>
.profile-page {
  display: grid;
  gap: 1rem;
  width: min(100%, 960px);
  margin: 0 auto;
  padding: 2rem;
}

.profile-header {
  display: flex;
  align-items: center;
  gap: 1rem;
  min-width: 0;
}

.profile-avatar {
  display: grid;
  place-items: center;
  width: 4.5rem;
  height: 4.5rem;
  border-radius: 50%;
  background: #e5eef8;
  color: #14213d;
  font-size: 1.45rem;
  font-weight: 800;
}

.profile-header h1 {
  margin: 0;
  color: var(--app-text);
  font-size: 2rem;
  line-height: 1.05;
}

.profile-header p {
  margin: 0.25rem 0 0;
  color: var(--app-muted);
}
</style>
