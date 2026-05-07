<script setup lang="ts">
import { computed, ref } from 'vue'
import Button from 'primevue/button'
import Card from 'primevue/card'
import Menu from 'primevue/menu'
import type { ExplorerFolder } from '../../api/explorerClient'
import ExplorerScopeBadge from './ExplorerScopeBadge.vue'

const props = defineProps<{
  folder: ExplorerFolder
}>()

const emit = defineEmits<{
  open: [folder: ExplorerFolder]
  edit: [folder: ExplorerFolder]
  move: [folder: ExplorerFolder]
  delete: [folder: ExplorerFolder]
}>()

const menu = ref()
const menuItems = computed(() => [
  {
    label: 'Bearbeiten',
    icon: 'pi pi-pencil',
    command: () => emit('edit', props.folder)
  },
  {
    label: 'Verschieben',
    icon: 'pi pi-arrow-right-arrow-left',
    command: () => emit('move', props.folder)
  },
  {
    label: 'Löschen',
    icon: 'pi pi-trash',
    class: 'explorer-menu-danger',
    command: () => emit('delete', props.folder)
  }
])

function toggleMenu(event: Event) {
  menu.value.toggle(event)
}
</script>

<template>
  <Card class="explorer-card explorer-card-folder">
    <template #content>
      <button class="explorer-card-main" type="button" @click="$emit('open', folder)">
        <span class="explorer-card-icon pi pi-folder" />
        <span class="explorer-card-text">
          <strong>{{ folder.displayName }}</strong>
          <small v-if="folder.ownerBadge">
            <ExplorerScopeBadge :owner-badge="folder.ownerBadge" />
          </small>
        </span>
      </button>
      <Button
        icon="pi pi-ellipsis-v"
        text
        rounded
        severity="secondary"
        class="explorer-card-menu-button"
        aria-label="Ordneraktionen"
        @click="toggleMenu"
      />
      <Menu ref="menu" :model="menuItems" popup class="explorer-card-menu" />
    </template>
  </Card>
</template>
