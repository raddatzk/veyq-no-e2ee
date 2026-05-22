<script setup lang="ts">
import { computed, ref } from 'vue'
import Button from 'primevue/button'
import Tab from 'primevue/tab'
import TabList from 'primevue/tablist'
import TabPanel from 'primevue/tabpanel'
import TabPanels from 'primevue/tabpanels'
import Tabs from 'primevue/tabs'
import MediaGrid from '../../../components/media/MediaGrid.vue'
import { API_BASE } from '../../../shared/api/httpClient'
import type { Memory, MemoryMedia } from '../api'
import DeleteMemoryDialog from './DeleteMemoryDialog.vue'
import EditMemoryDialog from './EditMemoryDialog.vue'
import MemoryCard from './MemoryCard.vue'
import MemoryCoverDialog from './MemoryCoverDialog.vue'

const props = defineProps<{
  tenantName: string
  memory: Memory
  childMemories: Memory[]
  media: MemoryMedia[]
}>()

defineEmits<{
  openMemory: [memory: Memory]
  createChildMemory: []
  changed: [memory: Memory]
  deleted: [parentPath: string]
}>()

const editOpen = ref(false)
const coverOpen = ref(false)
const deleteOpen = ref(false)

const coverMedia = computed(() =>
  props.memory.coverMediaId
    ? props.media.find((media) => media.id === props.memory.coverMediaId) || props.media[0]
    : props.media[0]
)
const coverUrl = computed(() => {
  if (props.memory.coverUrl) return `${API_BASE}${props.memory.coverUrl}`
  const media = coverMedia.value
  if (!media) return ''
  return `${API_BASE}${media.previewUrl || media.contentUrl}`
})
const scopeLabel = computed(() => {
  if (props.memory.scope?.type === 'GROUP') return `@${props.memory.scope.groupName}`
  if (props.memory.scope?.type === 'USER') return 'Persönlich'
  return 'Tenant'
})
const parentPath = computed(() => {
  const segments = props.memory.path.split('/').filter(Boolean)
  if (segments.length <= 1) return '/'
  return `/${segments.slice(0, -1).join('/')}`
})
</script>

<template>
  <div class="memory-detail">
    <header class="memory-detail-header">
      <div class="memory-detail-cover">
        <img v-if="coverUrl" :src="coverUrl" :alt="memory.displayName" />
        <span v-else class="pi pi-images" />
      </div>
      <div class="memory-detail-heading">
        <div class="memory-detail-title-row">
          <div>
            <p>Memory</p>
            <h1>{{ memory.displayName }}</h1>
          </div>
          <div class="memory-detail-actions">
            <Button
              icon="pi pi-pencil"
              label="Bearbeiten"
              severity="secondary"
              outlined
              size="small"
              @click="editOpen = true"
            />
            <Button
              icon="pi pi-image"
              label="Cover"
              severity="secondary"
              outlined
              size="small"
              @click="coverOpen = true"
            />
            <Button
              icon="pi pi-trash"
              label="Löschen"
              severity="danger"
              outlined
              size="small"
              @click="deleteOpen = true"
            />
          </div>
        </div>
        <div class="memory-detail-meta">
          <span><i class="pi pi-folder" /> {{ scopeLabel }}</span>
          <span><i class="pi pi-images" /> {{ memory.mediaCount }} Medien</span>
          <span><i class="pi pi-sitemap" /> {{ memory.childCount }} Unter-Memories</span>
        </div>
      </div>
    </header>

    <Tabs value="gallery" class="memory-tabs">
      <TabList>
        <Tab value="gallery">Galerie</Tab>
        <Tab value="children">Unter-Memories</Tab>
      </TabList>
      <TabPanels>
        <TabPanel value="gallery">
          <MediaGrid :tenant-name="tenantName" :media="media" :streaming="false" />
        </TabPanel>
        <TabPanel value="children">
          <div class="memory-card-grid">
            <MemoryCard
              v-for="child in childMemories"
              :key="child.id"
              :memory="child"
              @open="$emit('openMemory', $event)"
            />
            <button class="memory-create-card" type="button" @click="$emit('createChildMemory')">
              <span class="pi pi-plus" />
              <strong>Unter-Memory erstellen</strong>
            </button>
          </div>
        </TabPanel>
      </TabPanels>
    </Tabs>

    <EditMemoryDialog
      v-model:visible="editOpen"
      :tenant-name="tenantName"
      :memory="memory"
      @saved="$emit('changed', $event)"
    />
    <MemoryCoverDialog
      v-model:visible="coverOpen"
      :tenant-name="tenantName"
      :memory="memory"
      @saved="$emit('changed', $event)"
    />
    <DeleteMemoryDialog
      v-model:visible="deleteOpen"
      :tenant-name="tenantName"
      :memory="memory"
      @deleted="$emit('deleted', parentPath)"
    />
  </div>
</template>
