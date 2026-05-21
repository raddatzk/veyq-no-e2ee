<script setup lang="ts">
import { computed } from 'vue'
import Button from 'primevue/button'
import Tab from 'primevue/tab'
import TabList from 'primevue/tablist'
import TabPanel from 'primevue/tabpanel'
import TabPanels from 'primevue/tabpanels'
import Tabs from 'primevue/tabs'
import MediaGrid from '../../../components/media/MediaGrid.vue'
import { API_BASE } from '../../../shared/api/httpClient'
import type { Memory, MemoryMedia } from '../api'
import MemoryCard from './MemoryCard.vue'

const props = defineProps<{
  tenantName: string
  memory: Memory
  childMemories: Memory[]
  media: MemoryMedia[]
}>()

defineEmits<{
  openMemory: [memory: Memory]
  createChildMemory: []
}>()

const coverMedia = computed(() =>
  props.memory.coverMediaId
    ? props.media.find((media) => media.id === props.memory.coverMediaId) || props.media[0]
    : props.media[0]
)
const coverUrl = computed(() => {
  const media = coverMedia.value
  if (!media) return ''
  return `${API_BASE}${media.previewUrl || media.contentUrl}`
})
const scopeLabel = computed(() => {
  if (props.memory.scope?.type === 'GROUP') return `@${props.memory.scope.groupName}`
  if (props.memory.scope?.type === 'USER') return 'Persönlich'
  return 'Tenant'
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
            <Button icon="pi pi-filter" label="Filter" severity="secondary" outlined size="small" />
            <Button icon="pi pi-users" label="Einladen" severity="secondary" outlined size="small" />
          </div>
        </div>
        <div class="memory-detail-meta">
          <span><i class="pi pi-folder" /> {{ scopeLabel }}</span>
          <span><i class="pi pi-images" /> {{ memory.mediaCount }} Medien</span>
          <span><i class="pi pi-sitemap" /> {{ memory.childCount }} Child Memories</span>
        </div>
      </div>
    </header>

    <Tabs value="gallery" class="memory-tabs">
      <TabList>
        <Tab value="gallery">Gallery</Tab>
        <Tab value="children">Child Memories</Tab>
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
              <strong>Child Memory erstellen</strong>
            </button>
          </div>
        </TabPanel>
      </TabPanels>
    </Tabs>
  </div>
</template>
