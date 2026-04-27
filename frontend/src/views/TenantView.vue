<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { authState } from '../authState'
import { createGroup, groups, media, type TenantGroup } from '../api/tenantClient'

const route = useRoute()
const tenantName = computed(() => String(route.params.tenantName || ''))
const tenant = computed(() => authState.tenants.find((item) => item.slug === tenantName.value))
const tenantGroups = ref<TenantGroup[]>([])
const mediaCount = ref(0)
const groupName = ref('')
const error = ref('')

async function loadTenant() {
  if (!tenantName.value) return
  error.value = ''
  try {
    const [groupItems, mediaItems] = await Promise.all([
      groups(tenantName.value),
      media(tenantName.value)
    ])
    tenantGroups.value = groupItems
    mediaCount.value = mediaItems.length
  } catch (e: any) {
    error.value = e.message || 'Tenant konnte nicht geladen werden'
  }
}

async function submitGroup() {
  if (!groupName.value.trim()) return
  error.value = ''
  try {
    const group = await createGroup(tenantName.value, groupName.value)
    tenantGroups.value = [...tenantGroups.value, group].sort((a, b) => a.name.localeCompare(b.name))
    groupName.value = ''
  } catch (e: any) {
    error.value = e.message || 'Gruppe konnte nicht erstellt werden'
  }
}

onMounted(loadTenant)
watch(tenantName, loadTenant)
</script>

<template>
  <section class="card">
    <h1>{{ tenant?.name || tenantName }}</h1>
    <p v-if="tenant">Rolle: {{ tenant.role }}</p>
    <p v-if="error" class="error">{{ error }}</p>

    <div class="tenant-stats">
      <div>
        <strong>{{ mediaCount }}</strong>
        <span>Medien</span>
      </div>
      <div>
        <strong>{{ tenantGroups.length }}</strong>
        <span>Gruppen</span>
      </div>
    </div>

    <form class="inline-form" @submit.prevent="submitGroup">
      <input v-model="groupName" placeholder="Neue Gruppe" />
      <button type="submit">Erstellen</button>
    </form>

    <div class="tenant-list">
      <div v-for="group in tenantGroups" :key="group.id" class="tenant-row">
        <span>{{ group.name }}</span>
      </div>
    </div>
  </section>
</template>
