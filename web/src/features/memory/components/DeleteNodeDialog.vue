<script setup lang="ts">
// Delete a folder (and its subtree) or a Memory (cr-0030 P4). Wired to the E2EE tree data layer.
import { ref, watch, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import Message from 'primevue/message'
import { deleteNode, type TreeNode } from '../tree'
import { NodeKind } from '../../../api/generated/content'

const visible = defineModel<boolean>('visible', { required: true })
const props = defineProps<{ scopeId: string; node: TreeNode }>()
const emit = defineEmits<{ done: [] }>()
const { t } = useI18n()

const error = ref('')
const loading = ref(false)
const isFolder = computed(() => props.node.kind === NodeKind.Folder)
const header = computed(() => (isFolder.value ? t('memory.deleteFolderTitle') : t('memory.deleteMemoryTitle')))
const copy = computed(() =>
  isFolder.value
    ? t('memory.deleteFolderCopy', { name: props.node.name })
    : t('memory.deleteMemoryCopy', { name: props.node.name }),
)

watch(visible, (open) => {
  if (open) error.value = ''
})

async function confirm() {
  error.value = ''
  loading.value = true
  try {
    await deleteNode(props.scopeId, props.node.id)
    visible.value = false
    emit('done')
  } catch {
    error.value = t('memory.deleteFailed')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <Dialog v-model:visible="visible" :header="header" modal>
    <p class="dialog-copy">{{ copy }}</p>
    <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>
    <div class="dialog-actions">
      <Button type="button" :label="t('common.cancel')" icon="pi pi-times" severity="secondary" outlined @click="visible = false" />
      <Button type="button" :label="t('memory.delete')" icon="pi pi-trash" severity="danger" :loading="loading" @click="confirm" />
    </div>
  </Dialog>
</template>

<style scoped>
.dialog-copy {
  margin: 0 0 0.75rem;
}
.dialog-actions {
  display: flex;
  justify-content: flex-end;
  gap: 0.5rem;
  margin-top: 0.5rem;
}
</style>
