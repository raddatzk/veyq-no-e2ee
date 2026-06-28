<script setup lang="ts">
// Create a folder / create a Memory / rename a node (cr-0030 P4). Ported dialog shape; wired to the E2EE
// tree data layer (names sealed under K_SC client-side). One dialog, three modes. Inline slug_taken (409) /
// illegal_structure (422) errors.
import { ref, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import { createFolder, createMemory, renameNode, type TreeNode } from '../tree'

type Mode = 'create-folder' | 'create-memory' | 'rename'

// Mounted via v-if while open; closing = the parent unmounting it on `close` (robust — no two-way `visible`).
const props = defineProps<{
  mode: Mode
  scopeId: string
  scopeKey: Uint8Array
  parentId?: string | null // create: the parent (null = root)
  node?: TreeNode | null // rename: the node being renamed
}>()
const emit = defineEmits<{ done: []; close: [] }>()
const { t } = useI18n()

const name = ref(props.mode === 'rename' ? (props.node?.name ?? '') : '')
const error = ref('')
const loading = ref(false)

const header = computed(() =>
  props.mode === 'rename'
    ? t('memory.renameTitle')
    : props.mode === 'create-memory'
      ? t('memory.createMemoryTitle')
      : t('memory.createFolderTitle'),
)
const confirmLabel = computed(() => (props.mode === 'rename' ? t('memory.save') : t('common.create')))
const canSubmit = computed(() => name.value.trim().length > 0 && !loading.value)

async function submit() {
  if (!canSubmit.value) return
  error.value = ''
  loading.value = true
  try {
    const trimmed = name.value.trim()
    if (props.mode === 'create-folder') {
      await createFolder(props.scopeId, props.scopeKey, trimmed, props.parentId ?? null)
    } else if (props.mode === 'create-memory') {
      await createMemory(props.scopeId, props.scopeKey, trimmed, props.parentId ?? null)
    } else if (props.node) {
      await renameNode(props.scopeId, props.scopeKey, { id: props.node.id, parentId: props.node.parentId }, trimmed)
    }
    emit('done')
    emit('close')
  } catch (e) {
    const status = (e as { response?: { status?: number } }).response?.status
    error.value = status === 409 ? t('memory.slugTaken') : status === 422 ? t('memory.illegalStructure') : t('memory.saveFailed')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <Dialog :visible="true" :header="header" modal @update:visible="emit('close')">
    <form class="veyq-form" @submit.prevent="submit">
      <label>
        {{ t('memory.nameLabel') }}
        <InputText v-model="name" autocomplete="off" required autofocus />
      </label>
      <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>
      <div class="dialog-actions">
        <Button type="button" :label="t('common.cancel')" icon="pi pi-times" severity="secondary" outlined @click="emit('close')" />
        <Button type="submit" :label="confirmLabel" icon="pi pi-check" :loading="loading" :disabled="!canSubmit" />
      </div>
    </form>
  </Dialog>
</template>

<style scoped>
.dialog-actions {
  display: flex;
  justify-content: flex-end;
  gap: 0.5rem;
  margin-top: 0.5rem;
}
</style>
