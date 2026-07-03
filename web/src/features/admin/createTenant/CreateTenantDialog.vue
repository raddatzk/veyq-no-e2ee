<script setup lang="ts">
// Create a tenant shell + owner invite (uc.admin.create-tenant, Slice A). Ported from the legacy dialog;
// the data layer is swapped to the generated tenants client. Creating the shell never makes the current
// Server Owner a tenant member or key-holder — ownership is claimed only when the invite is opened (Slice B).
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import { createTenant, tenantError } from './tenants'

const visible = defineModel<boolean>('visible', { required: true })
const emit = defineEmits<{ created: [] }>()
const { t } = useI18n()

const name = ref('')
const domain = ref('')
const ownerEmail = ref('')
const error = ref('')
const loading = ref(false)
const canSubmit = computed(
  () => name.value.trim().length > 0 && domain.value.trim().length > 0 && ownerEmail.value.trim().length > 0,
)

watch(visible, (isVisible) => {
  if (isVisible) {
    error.value = ''
    name.value = ''
    domain.value = ''
    ownerEmail.value = ''
  }
})

async function submit() {
  if (!canSubmit.value) return
  error.value = ''
  loading.value = true
  try {
    await createTenant(name.value.trim(), domain.value.trim().toLowerCase(), ownerEmail.value.trim())
    visible.value = false
    emit('created')
  } catch (e) {
    const { code } = await tenantError(e)
    error.value = code === 'domain_taken' ? t('tenant.domainTaken') : t('tenant.createFailed')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <Dialog v-model:visible="visible" :header="t('tenant.create')" modal class="tenant-dialog">
    <form class="veyq-form" @submit.prevent="submit">
      <label>
        {{ t('tenant.name') }}
        <InputText v-model="name" autocomplete="off" required />
      </label>
      <label>
        {{ t('tenant.domain') }}
        <InputText v-model="domain" autocomplete="off" required />
        <small class="field-hint">{{ t('tenant.domainHint') }}</small>
      </label>
      <label>
        {{ t('tenant.ownerEmail') }}
        <InputText v-model="ownerEmail" type="email" autocomplete="email" required />
      </label>
      <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>
      <div class="veyq-dialog-action-row">
        <Button
          type="button"
          :label="t('common.cancel')"
          icon="pi pi-times"
          severity="secondary"
          outlined
          @click="visible = false"
        />
        <Button type="submit" :label="t('common.create')" icon="pi pi-plus" :loading="loading" :disabled="!canSubmit" />
      </div>
    </form>
  </Dialog>
</template>

<style scoped>
.field-hint {
  display: block;
  opacity: 0.7;
  font-size: 0.85em;
}
</style>
