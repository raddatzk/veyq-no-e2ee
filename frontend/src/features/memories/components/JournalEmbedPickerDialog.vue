<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import InputText from 'primevue/inputtext'

type JournalEmbedPickerItem = {
  id: string
  title: string
  meta?: string
  icon: string
}

const props = defineProps<{
  visible: boolean
  title: string
  copy: string
  empty: string
  confirmLabel: string
  fieldLabel: string
  embedLabel: string
  labelPlaceholder: string
  selectedIds: string[]
  items: JournalEmbedPickerItem[]
  multiple?: boolean
  canDelete?: boolean
}>()

const emit = defineEmits<{
  'update:visible': [visible: boolean]
  'update:embedLabel': [label: string]
  labelInput: []
  select: [itemId: string]
  cancel: []
  confirm: []
  delete: []
}>()

const { t } = useI18n()

const dialogVisible = computed({
  get: () => props.visible,
  set: (value: boolean) => emit('update:visible', value)
})
</script>

<template>
  <Dialog
    v-model:visible="dialogVisible"
    :header="title"
    modal
    class="memory-journal-embed-dialog"
    @hide="$emit('cancel')"
  >
    <div class="memory-journal-embed-picker">
      <p class="memory-dialog-copy">{{ copy }}</p>
      <label class="memory-journal-field">
        <span>{{ fieldLabel }}</span>
        <InputText
          :model-value="embedLabel"
          :placeholder="labelPlaceholder"
          @update:model-value="$emit('update:embedLabel', $event ?? '')"
          @input="$emit('labelInput')"
        />
      </label>
      <div v-if="items.length" class="memory-journal-embed-picker-list">
        <button
          v-for="item in items"
          :key="item.id"
          type="button"
          class="memory-journal-embed-option"
          :class="{ 'memory-journal-embed-option-selected': selectedIds.includes(item.id) }"
          @click="$emit('select', item.id)"
        >
          <span class="memory-journal-embed-option-icon">
            <span :class="item.icon" />
          </span>
          <span class="memory-journal-embed-option-body">
            <strong>{{ item.title }}</strong>
            <small v-if="item.meta">{{ item.meta }}</small>
          </span>
          <span class="memory-journal-embed-option-check">
            <span v-if="selectedIds.includes(item.id)" class="pi pi-check" />
          </span>
        </button>
      </div>
      <div v-else class="memory-journal-empty">
        <span class="pi pi-inbox" />
        <p>{{ empty }}</p>
      </div>
    </div>
    <template #footer>
      <div class="memory-journal-editor-actions">
        <Button
          v-if="canDelete"
          class="memory-journal-dialog-delete"
          icon="pi pi-trash"
          :label="t('common.delete')"
          severity="danger"
          outlined
          @click="$emit('delete')"
        />
        <Button
          icon="pi pi-times"
          :label="t('common.cancel')"
          severity="secondary"
          outlined
          @click="$emit('cancel')"
        />
        <Button
          icon="pi pi-check"
          :label="confirmLabel"
          :disabled="selectedIds.length === 0"
          @click="$emit('confirm')"
        />
      </div>
    </template>
  </Dialog>
</template>
