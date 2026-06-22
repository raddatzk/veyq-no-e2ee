<script setup lang="ts">
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import { useI18n } from 'vue-i18n'

const visible = defineModel<boolean>('visible', { required: true })
const props = withDefaults(
  defineProps<{
    title: string
    confirmLabel: string
    confirmIcon?: string
    confirmSeverity?: 'secondary' | 'success' | 'info' | 'warn' | 'help' | 'danger' | 'contrast'
    confirmDisabled?: boolean
    loading?: boolean
    dialogClass?: string
    formClass?: string
  }>(),
  {
    confirmIcon: 'pi pi-check',
    confirmSeverity: undefined,
    confirmDisabled: false,
    loading: false,
    dialogClass: 'tenant-dialog',
    formClass: ''
  }
)
const emit = defineEmits<{
  confirm: []
}>()
const { t } = useI18n()

function updateVisible(nextVisible: boolean) {
  if (!nextVisible && props.loading) return
  visible.value = nextVisible
}

function cancel() {
  if (props.loading) return
  visible.value = false
}

function confirm() {
  if (props.loading || props.confirmDisabled) return
  emit('confirm')
}
</script>

<template>
  <Dialog
    :visible="visible"
    :header="title"
    modal
    :class="dialogClass"
    :closable="!loading"
    :close-on-escape="!loading"
    @update:visible="updateVisible"
  >
    <form class="veyq-form" :class="formClass" @submit.prevent="confirm">
      <slot :disabled="loading" />
      <div class="veyq-dialog-action-row">
        <Button
          type="button"
          :label="t('common.cancel')"
          icon="pi pi-times"
          severity="secondary"
          outlined
          :disabled="loading"
          @click="cancel"
        />
        <Button
          type="submit"
          :label="confirmLabel"
          :icon="confirmIcon"
          :severity="confirmSeverity"
          :disabled="confirmDisabled || loading"
          :loading="loading"
        />
      </div>
    </form>
  </Dialog>
</template>
