<script setup lang="ts">
import { ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Message from 'primevue/message'
import type { Memory, MemoryFolder, MemoryNavigationResponse } from '../api'
import { deleteFolder } from '../api'
import ConfirmationDialog from '../../../shared/components/ConfirmationDialog.vue'
import { getErrorMessage } from '../../../shared/utils/errors'
import MemoryCard from './MemoryCard.vue'

const { t } = useI18n()

const props = defineProps<{
  tenantName: string
  navigation: MemoryNavigationResponse
  memories: Memory[]
}>()

const emit = defineEmits<{
  openFolder: [folder: MemoryFolder]
  openMemory: [memory: Memory]
  createFolder: []
  createMemory: []
  deletedFolder: []
}>()

const deleteDialogOpen = ref(false)
const deleteLoading = ref(false)
const deleteError = ref('')

watch(deleteDialogOpen, (isOpen) => {
  if (!isOpen) deleteError.value = ''
})

function openDeleteDialog() {
  deleteError.value = ''
  deleteDialogOpen.value = true
}

async function confirmDeleteFolder() {
  deleteError.value = ''
  deleteLoading.value = true
  try {
    await deleteFolder(props.tenantName, props.navigation.location.path)
    deleteDialogOpen.value = false
    emit('deletedFolder')
  } catch (unknownError) {
    deleteError.value = getErrorMessage(unknownError, t('memory.folderDeleteFailed'))
  } finally {
    deleteLoading.value = false
  }
}
</script>

<template>
  <div class="memory-route-surface">
    <section v-if="navigation.location.kind !== 'TENANT'" class="veyq-section memory-section">
      <div class="veyq-section-header">
        <div class="veyq-card memory-folder-card memory-folder-overview-card">
          <span class="pi pi-folder"></span>
          <h2>{{ navigation.location.displayName }}</h2>
          <div class="memory-folder-overview-actions">
            <Button
              v-tooltip.bottom="t('common.delete')"
              type="button"
              class="veyq-icon-button veyq-prime-icon-button memory-folder-overview-action memory-danger-action memory-action-inline memory-action-collapsible"
              icon="pi pi-trash"
              :aria-label="t('common.delete')"
              severity="danger"
              outlined
              size="small"
              @click="openDeleteDialog"
            />
          </div>
        </div>
      </div>
    </section>
    <section class="veyq-section memory-section">
      <div class="veyq-section-header memory-section-header">
        <h2>{{ t('memory.memories') }}</h2>
        <span>{{ memories.length }}</span>
      </div>
      <div class="memory-card-grid memory-card-grid-memories">
        <p v-if="!memories.length" class="veyq-empty veyq-empty-inline">
          {{ t('memory.memoryEmpty') }}
        </p>
        <MemoryCard
          v-for="memory in memories"
          :key="memory.id"
          :memory="memory"
          @open="$emit('openMemory', $event)"
        />
        <button
          class="veyq-card veyq-card-interactive memory-create-card memory-create-card-memory"
          type="button"
          @click="$emit('createMemory')"
        >
          <span class="pi pi-plus" />
          <strong>{{ t('memory.create') }}</strong>
        </button>
      </div>
    </section>

    <section class="veyq-section memory-section">
      <div class="veyq-section-header memory-section-header">
        <h2>{{ t('memory.folders') }}</h2>
        <span>{{ navigation.folders.length }}</span>
      </div>
      <div class="memory-card-grid">
        <p v-if="!navigation.folders.length" class="veyq-empty veyq-empty-inline">
          {{ t('memory.folderEmpty') }}
        </p>
        <button
          v-for="folder in navigation.folders"
          :key="folder.path"
          class="veyq-card veyq-card-interactive memory-folder-card"
          type="button"
          @click="$emit('openFolder', folder)"
        >
          <span class="pi pi-folder" />
          <strong>{{ folder.displayName }}</strong>
        </button>
        <button
          class="veyq-card veyq-card-interactive memory-create-card"
          type="button"
          @click="$emit('createFolder')"
        >
          <span class="pi pi-plus" />
          <strong>{{ t('memory.folderCreate') }}</strong>
        </button>
      </div>
    </section>

    <ConfirmationDialog
      v-model:visible="deleteDialogOpen"
      :title="t('memory.folderDelete')"
      :confirm-label="t('memory.folderDelete')"
      confirm-icon="pi pi-trash"
      confirm-severity="danger"
      :loading="deleteLoading"
      @confirm="confirmDeleteFolder"
    >
      <p class="memory-dialog-copy">
        {{ t('memory.folderDeleteCopy', { name: navigation.location.displayName }) }}
      </p>
      <Message v-if="deleteError" severity="error" :closable="false">{{ deleteError }}</Message>
    </ConfirmationDialog>
  </div>
</template>
