<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import Button from 'primevue/button'
import Card from 'primevue/card'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import Tag from 'primevue/tag'
import { authState } from '../authState'
import { createGroup, groups, type TenantGroup } from '../api/groupClient'
import { media } from '../api/mediaClient'

const route = useRoute()
const tenantName = computed(() => String(route.params.tenantName || ''))
const tenant = computed(() => authState.tenants.find((item) => item.name === tenantName.value))
const tenantGroups = ref<TenantGroup[]>([])
const mediaCount = ref(0)
const groupName = ref('')
const groupDisplayName = ref('')
const error = ref('')

const tenantTitle = computed(() => tenant.value?.displayName || tenantName.value)
const roleLabel = computed(() => tenant.value?.role === 'TENANT_OWNER' ? 'Owner' : 'Member')

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
  const name = groupName.value.trim()
  const displayName = groupDisplayName.value.trim() || name
  if (!name) return
  error.value = ''
  try {
    const group = await createGroup(tenantName.value, name, displayName)
    tenantGroups.value = [...tenantGroups.value, group].sort((a, b) => a.displayName.localeCompare(b.displayName))
    groupName.value = ''
    groupDisplayName.value = ''
  } catch (e: any) {
    error.value = e.message || 'Gruppe konnte nicht erstellt werden'
  }
}

onMounted(loadTenant)
watch(tenantName, loadTenant)
</script>

<template>
  <Card>
    <template #title>{{ tenantTitle }}</template>
    <template #subtitle>
      <Tag v-if="tenant" :value="roleLabel" severity="info" />
    </template>
    <template #content>
    <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>

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
      <InputText v-model="groupName" placeholder="gruppen-slug" />
      <InputText v-model="groupDisplayName" placeholder="Neue Gruppe" />
      <Button type="submit" label="Erstellen" icon="pi pi-plus" />
    </form>

    <div class="tenant-list">
      <div v-for="group in tenantGroups" :key="group.id" class="tenant-row">
        <span>{{ group.displayName }}</span>
        <small>{{ group.name }}</small>
      </div>
    </div>
    </template>
  </Card>
</template>
