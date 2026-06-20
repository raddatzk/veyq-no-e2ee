<script setup lang="ts">
import { ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import Message from 'primevue/message'
import ProgressSpinner from 'primevue/progressspinner'
import TenantView from './TenantView.vue'
import { parseHomePath } from '../features/home/homeRoute'
import { resolveRoute, type ActiveSpace, type LevelEntry } from '../features/home/home'
import { ensureActiveSpaceForRoute } from '../app/spaces'
import { session } from '../app/session'
import { t } from '../i18n/i18n'

const route = useRoute()
const loading = ref(true)
const error = ref('')
const active = ref<ActiveSpace | null>(null)
const folders = ref<LevelEntry[]>([])
const memory = ref<LevelEntry | null>(null)

async function resolve() {
  loading.value = true
  error.value = ''
  try {
    const parsed = parseHomePath(route.path)
    const space = await ensureActiveSpaceForRoute(parsed)
    active.value = space
    const r = await resolveRoute(session.apiBase, session.unlocked!, space, parsed?.segments ?? [])
    folders.value = r.folders
    memory.value = r.memory
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

watch(() => route.path, resolve, { immediate: true })
</script>

<template>
  <div class="veyq-page memory-page">
    <div v-if="loading" class="veyq-loading">
      <ProgressSpinner class="small-spinner" />
      <span>{{ t('app.loading') }}</span>
    </div>
    <Message v-else-if="error" severity="error" :closable="false">{{ error }}</Message>
    <TenantView v-else-if="active" :active="active" :folders="folders" :memory="memory" />
  </div>
</template>
