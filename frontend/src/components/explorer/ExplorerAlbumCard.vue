<script setup lang="ts">
import { computed, ref } from 'vue'
import Button from 'primevue/button'
import Card from 'primevue/card'
import Menu from 'primevue/menu'
import type { ExplorerAlbum } from '../../api/explorerClient'
import ExplorerScopeBadge from './ExplorerScopeBadge.vue'

const props = defineProps<{
  album: ExplorerAlbum
}>()

const emit = defineEmits<{
  open: [album: ExplorerAlbum]
  edit: [album: ExplorerAlbum]
  move: [album: ExplorerAlbum]
  cover: [album: ExplorerAlbum]
  delete: [album: ExplorerAlbum]
}>()

const menu = ref()
const menuItems = computed(() => [
  {
    label: 'Bearbeiten',
    icon: 'pi pi-pencil',
    command: () => emit('edit', props.album)
  },
  {
    label: 'Verschieben',
    icon: 'pi pi-arrow-right-arrow-left',
    command: () => emit('move', props.album)
  },
  {
    label: 'Cover setzen',
    icon: 'pi pi-image',
    command: () => emit('cover', props.album)
  },
  {
    label: 'Löschen',
    icon: 'pi pi-trash',
    class: 'explorer-menu-danger',
    command: () => emit('delete', props.album)
  }
])

function toggleMenu(event: Event) {
  menu.value.toggle(event)
}
</script>

<template>
  <Card class="explorer-card explorer-card-album">
    <template #content>
      <button class="explorer-card-main" type="button" @click="$emit('open', album)">
        <span class="explorer-card-icon pi pi-images" />
        <span class="explorer-card-text">
          <strong>{{ album.displayName }}</strong>
          <small v-if="album.ownerBadge">
            <ExplorerScopeBadge :owner-badge="album.ownerBadge" />
          </small>
        </span>
      </button>
      <Button
        icon="pi pi-ellipsis-v"
        text
        rounded
        severity="secondary"
        class="explorer-card-menu-button"
        aria-label="Albumaktionen"
        @click="toggleMenu"
      />
      <Menu ref="menu" :model="menuItems" popup class="explorer-card-menu" />
    </template>
  </Card>
</template>
