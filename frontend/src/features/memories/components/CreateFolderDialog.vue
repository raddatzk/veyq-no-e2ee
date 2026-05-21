<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import Select from 'primevue/select'
import { groups, type TenantGroup } from '../../../api/groupClient'
import { toPermalink } from '../../../composables/usePermalink'
import { useAuthState } from '../../../shared/state/useAuthState'
import { getErrorMessage } from '../../../shared/utils/errors'
import { createFolder } from '../api'

const visible = defineModel<boolean>('visible', { required: true })
const props = defineProps<{
  tenantName: string
  tenantDisplayName: string
  parentPath: string
}>()
const emit = defineEmits<{
  saved: []
}>()

const { authState } = useAuthState()
const displayName = ref('')
const name = ref('')
const permalinkTouched = ref(false)
const error = ref('')
const loading = ref(false)
const availableGroups = ref<TenantGroup[]>([])
const scopeValue = ref('TENANT')
const isRootCreate = computed(() => !props.parentPath || props.parentPath === '/')
const scopeOptions = computed(() => [
  { label: props.tenantDisplayName, value: 'TENANT' },
  ...availableGroups.value.map((group) => ({
    label: `@${group.displayName}`,
    value: `GROUP:${group.name}`
  })),
  { label: `~${authState.user?.displayName || 'me'}`, value: 'USER' }
])

watch(displayName, (value) => {
  if (!permalinkTouched.value) name.value = toPermalink(value)
})

watch(visible, (isVisible) => {
  if (!isVisible) return
  displayName.value = ''
  name.value = ''
  permalinkTouched.value = false
  error.value = ''
  scopeValue.value = 'TENANT'
  if (isRootCreate.value) void loadGroups()
})

function updatePermalink(value: string | undefined) {
  permalinkTouched.value = true
  name.value = toPermalink(value || '')
}

async function submit() {
  const request = {
    parentPath: props.parentPath || '/',
    displayName: displayName.value.trim(),
    name: name.value.trim(),
    scope: rootScopeRequest()
  }
  if (!request.name || !request.displayName) return

  error.value = ''
  loading.value = true
  try {
    await createFolder(props.tenantName, request)
    visible.value = false
    emit('saved')
  } catch (unknownError) {
    error.value = getErrorMessage(unknownError, 'Ordner konnte nicht erstellt werden')
  } finally {
    loading.value = false
  }
}

async function loadGroups() {
  try {
    availableGroups.value = await groups(props.tenantName)
  } catch {
    availableGroups.value = []
  }
}

function rootScopeRequest() {
  if (!isRootCreate.value) return null
  if (scopeValue.value === 'USER') return { type: 'USER' as const }
  if (scopeValue.value.startsWith('GROUP:')) {
    return { type: 'GROUP' as const, groupName: scopeValue.value.substring('GROUP:'.length) }
  }
  return { type: 'TENANT' as const }
}
</script>

<template>
  <Dialog v-model:visible="visible" header="Ordner erstellen" modal class="tenant-dialog">
    <form class="form" @submit.prevent="submit">
      <label>
        Name
        <InputText v-model="displayName" autocomplete="off" required />
      </label>
      <label>
        Permalink
        <InputText
          :model-value="name"
          autocomplete="off"
          required
          @update:model-value="updatePermalink"
        />
      </label>
      <label v-if="isRootCreate">
        Bereich
        <Select
          v-model="scopeValue"
          :options="scopeOptions"
          option-label="label"
          option-value="value"
          class="scope-select"
        />
      </label>
      <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>
      <div class="button-row">
        <Button type="submit" label="Erstellen" icon="pi pi-plus" :loading="loading" />
        <Button
          type="button"
          label="Abbrechen"
          severity="secondary"
          outlined
          @click="visible = false"
        />
      </div>
    </form>
  </Dialog>
</template>
