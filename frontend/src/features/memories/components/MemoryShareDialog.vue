<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import Select from 'primevue/select'
import ToggleSwitch from 'primevue/toggleswitch'
import ManagementList from '../../../shared/components/ManagementList.vue'
import { getErrorMessage } from '../../../shared/utils/errors'
import {
  createMemoryShare,
  listMemoryShares,
  updateMemoryShare,
  type Memory,
  type MemoryShareRole,
  type MemoryShareSummary
} from '../api'

const visible = defineModel<boolean>('visible', { required: true })
const props = defineProps<{
  tenantName: string
  memory: Memory
}>()

const shares = ref<MemoryShareSummary[]>([])
const name = ref('')
const role = ref<MemoryShareRole>('VIEWER')
const passwordEnabled = ref(false)
const password = ref('')
const error = ref('')
const loading = ref(false)
const renameDialogVisible = ref(false)
const passwordDialogVisible = ref(false)
const selectedShare = ref<MemoryShareSummary | null>(null)
const editName = ref('')
const editPasswordEnabled = ref(false)
const editPassword = ref('')

const roleOptions = [
  { label: 'Viewer', value: 'VIEWER' },
  { label: 'Contributor', value: 'CONTRIBUTOR' },
  { label: 'Manager', value: 'MANAGER' },
  { label: 'Uploader', value: 'UPLOADER' }
]
const origin = computed(() => window.location.origin)

watch(
  visible,
  (isVisible) => {
    if (!isVisible) return
    name.value = ''
    role.value = 'VIEWER'
    passwordEnabled.value = false
    password.value = ''
    error.value = ''
    void loadShares()
  },
  { immediate: true }
)

async function loadShares() {
  loading.value = true
  try {
    shares.value = await listMemoryShares(props.tenantName, props.memory.id)
  } catch (unknownError) {
    error.value = getErrorMessage(unknownError, 'Shares konnten nicht geladen werden')
  } finally {
    loading.value = false
  }
}

async function createShare() {
  if (passwordEnabled.value && !password.value.trim()) return
  error.value = ''
  loading.value = true
  try {
    await createMemoryShare(props.tenantName, props.memory.id, {
      name: name.value.trim() || null,
      role: role.value,
      password: passwordEnabled.value ? password.value.trim() : null
    })
    name.value = ''
    passwordEnabled.value = false
    password.value = ''
    await loadShares()
  } catch (unknownError) {
    error.value = getErrorMessage(unknownError, 'Share konnte nicht erstellt werden')
  } finally {
    loading.value = false
  }
}

async function setRole(share: MemoryShareSummary, nextRole: MemoryShareRole) {
  await updateShare(share, { role: nextRole })
}

async function toggleShare(share: MemoryShareSummary) {
  await updateShare(share, { enabled: !share.enabled })
}

function openRenameDialog(share: MemoryShareSummary) {
  selectedShare.value = share
  editName.value = share.name || ''
  renameDialogVisible.value = true
}

function openPasswordDialog(share: MemoryShareSummary) {
  selectedShare.value = share
  editPasswordEnabled.value = share.hasPassword
  editPassword.value = ''
  passwordDialogVisible.value = true
}

async function saveName() {
  if (!selectedShare.value) return
  await updateShare(selectedShare.value, { name: editName.value.trim() || null })
  renameDialogVisible.value = false
  selectedShare.value = null
}

async function savePassword() {
  if (!selectedShare.value || !canSavePassword.value) return
  await updateShare(selectedShare.value, {
    password: editPasswordEnabled.value ? editPassword.value.trim() : ''
  })
  passwordDialogVisible.value = false
  selectedShare.value = null
  editPassword.value = ''
}

async function updateShare(
  share: MemoryShareSummary,
  request: { name?: string | null; role?: MemoryShareRole; enabled?: boolean; password?: string | null }
) {
  error.value = ''
  loading.value = true
  try {
    await updateMemoryShare(props.tenantName, share.id, request)
    await loadShares()
  } catch (unknownError) {
    error.value = getErrorMessage(unknownError, 'Share konnte nicht aktualisiert werden')
  } finally {
    loading.value = false
  }
}

async function copyShare(share: MemoryShareSummary) {
  await navigator.clipboard.writeText(`${origin.value}${share.shareUrl}`)
}

function shareLabel(share: MemoryShareSummary) {
  return share.name || share.role
}

const canCreateShare = computed(() => !passwordEnabled.value || Boolean(password.value.trim()))

const canSavePassword = computed(() => {
  if (!selectedShare.value) return false
  if (!editPasswordEnabled.value) return selectedShare.value.hasPassword
  return Boolean(editPassword.value.trim())
})
</script>

<template>
  <Dialog v-model:visible="visible" header="Memory teilen" modal class="tenant-dialog share-dialog">
    <div class="form">
      <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>

      <div class="form-grid">
        <label>
          Name
          <InputText v-model="name" autocomplete="off" placeholder="Optional" />
        </label>
        <label>
          Rolle
          <Select v-model="role" :options="roleOptions" option-label="label" option-value="value" />
        </label>
      </div>
      <label class="toggle-row">
        <span>
          <strong>Passwortschutz</strong>
          <small>Share nur mit Passwort öffnen</small>
        </span>
        <ToggleSwitch v-model="passwordEnabled" aria-label="Passwortschutz aktivieren" />
      </label>
      <label v-if="passwordEnabled">
        Passwort
        <InputText v-model="password" type="password" autocomplete="new-password" required />
      </label>
      <Button
        type="button"
        label="Share erstellen"
        icon="pi pi-share-alt"
        :loading="loading"
        :disabled="!canCreateShare"
        @click="createShare"
      />

      <ManagementList>
        <div v-for="share in shares" :key="share.id" class="management-row">
          <div class="management-row-main">
            <strong class="management-row-title">{{ shareLabel(share) }}</strong>
            <span class="management-row-subtitle">{{ origin }}{{ share.shareUrl }}</span>
            <div class="management-row-meta">
              <span
                class="management-badge"
                :class="share.hasPassword ? 'success' : 'muted'"
              >
                {{ share.hasPassword ? 'Passwort' : 'Ohne Passwort' }}
              </span>
              <span
                class="management-badge"
                :class="share.enabled ? 'success' : 'warning'"
              >
                {{ share.enabled ? 'Aktiv' : 'Deaktiviert' }}
              </span>
            </div>
          </div>
          <Select
            class="management-control"
            :model-value="share.role"
            :options="roleOptions"
            option-label="label"
            option-value="value"
            size="small"
            aria-label="Share-Rolle"
            @update:model-value="setRole(share, $event)"
          />
          <div class="management-actions">
            <Button
              icon="pi pi-copy"
              severity="secondary"
              outlined
              size="small"
              aria-label="Share-Link kopieren"
              title="Share-Link kopieren"
              @click="copyShare(share)"
            />
            <Button
              icon="pi pi-pencil"
              severity="secondary"
              outlined
              size="small"
              aria-label="Share umbenennen"
              title="Share umbenennen"
              @click="openRenameDialog(share)"
            />
            <Button
              :icon="share.enabled ? 'pi pi-lock' : 'pi pi-lock-open'"
              severity="secondary"
              outlined
              size="small"
              :aria-label="share.enabled ? 'Share deaktivieren' : 'Share aktivieren'"
              :title="share.enabled ? 'Share deaktivieren' : 'Share aktivieren'"
              @click="toggleShare(share)"
            />
            <Button
              icon="pi pi-key"
              severity="secondary"
              outlined
              size="small"
              aria-label="Passwort ändern"
              title="Passwort ändern"
              @click="openPasswordDialog(share)"
            />
          </div>
        </div>
      </ManagementList>
    </div>
  </Dialog>

  <Dialog v-model:visible="renameDialogVisible" header="Share umbenennen" modal class="tenant-dialog">
    <form class="form" @submit.prevent="saveName">
      <label>
        Name
        <InputText v-model="editName" autocomplete="off" placeholder="Optional" />
      </label>
      <div class="button-row">
        <Button type="submit" label="Speichern" icon="pi pi-save" :loading="loading" />
        <Button
          type="button"
          label="Abbrechen"
          severity="secondary"
          outlined
          @click="renameDialogVisible = false"
        />
      </div>
    </form>
  </Dialog>

  <Dialog v-model:visible="passwordDialogVisible" header="Passwortschutz" modal class="tenant-dialog">
    <form class="form" @submit.prevent="savePassword">
      <label class="toggle-row">
        <span>
          <strong>Passwortschutz</strong>
          <small>Share nur mit Passwort öffnen</small>
        </span>
        <ToggleSwitch v-model="editPasswordEnabled" aria-label="Passwortschutz aktivieren" />
      </label>
      <label v-if="editPasswordEnabled">
        Neues Passwort
        <InputText v-model="editPassword" type="password" autocomplete="new-password" required />
      </label>
      <div class="dialog-action-row">
        <div class="dialog-action-group">
          <Button
            type="button"
            label="Abbrechen"
            severity="secondary"
            text
            @click="passwordDialogVisible = false"
          />
          <Button
            type="submit"
            label="Speichern"
            icon="pi pi-save"
            :loading="loading"
            :disabled="!canSavePassword"
          />
        </div>
      </div>
    </form>
  </Dialog>
</template>
