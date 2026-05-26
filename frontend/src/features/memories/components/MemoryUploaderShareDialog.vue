<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import ToggleSwitch from 'primevue/toggleswitch'
import ManagementList from '../../../shared/components/ManagementList.vue'
import { getErrorMessage } from '../../../shared/utils/errors'
import {
  createMemoryShare,
  listMemoryShares,
  updateMemoryShare,
  type Memory,
  type MemoryShareSummary
} from '../api'

const visible = defineModel<boolean>('visible', { required: true })
const props = defineProps<{
  tenantName: string
  memory: Memory
}>()

const { t } = useI18n()
const shares = ref<MemoryShareSummary[]>([])
const name = ref('')
const passwordEnabled = ref(false)
const password = ref('')
const error = ref('')
const message = ref('')
const loading = ref(false)
const renameDialogVisible = ref(false)
const passwordDialogVisible = ref(false)
const selectedShare = ref<MemoryShareSummary | null>(null)
const editName = ref('')
const editPasswordEnabled = ref(false)
const editPassword = ref('')

const origin = computed(() => window.location.origin)

watch(
  visible,
  (isVisible) => {
    if (!isVisible) return
    name.value = ''
    passwordEnabled.value = false
    password.value = ''
    error.value = ''
    message.value = ''
    void loadShares()
  },
  { immediate: true }
)

async function loadShares() {
  loading.value = true
  try {
    shares.value = (await listMemoryShares(props.tenantName, props.memory.id)).filter(
      (share) => share.role === 'UPLOADER'
    )
  } catch (unknownError) {
    error.value = getErrorMessage(unknownError, t('share.loadFailed'))
  } finally {
    loading.value = false
  }
}

async function createUploaderShare() {
  if (passwordEnabled.value && !password.value.trim()) return
  error.value = ''
  message.value = ''
  loading.value = true
  try {
    await createMemoryShare(props.tenantName, props.memory.id, {
      name: name.value.trim() || null,
      role: 'UPLOADER',
      password: passwordEnabled.value ? password.value.trim() : null
    })
    name.value = ''
    passwordEnabled.value = false
    password.value = ''
    await loadShares()
  } catch (unknownError) {
    error.value = getErrorMessage(unknownError, t('share.createFailed'))
  } finally {
    loading.value = false
  }
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
  request: { name?: string | null; enabled?: boolean; password?: string | null }
) {
  error.value = ''
  message.value = ''
  loading.value = true
  try {
    await updateMemoryShare(props.tenantName, share.id, request)
    await loadShares()
  } catch (unknownError) {
    error.value = getErrorMessage(unknownError, t('share.updateFailed'))
  } finally {
    loading.value = false
  }
}

async function copyShare(share: MemoryShareSummary) {
  error.value = ''
  message.value = ''
  try {
    await navigator.clipboard.writeText(`${origin.value}${share.shareUrl}`)
    message.value = t('share.linkCopied')
  } catch {
    error.value = t('share.linkCopyFailed')
  }
}

function shareLabel(share: MemoryShareSummary) {
  return share.name || t('share.uploaderDefaultName')
}

const canCreateShare = computed(() => !passwordEnabled.value || Boolean(password.value.trim()))

const canSavePassword = computed(() => {
  if (!selectedShare.value) return false
  if (!editPasswordEnabled.value) return selectedShare.value.hasPassword
  return Boolean(editPassword.value.trim())
})
</script>

<template>
  <Dialog v-model:visible="visible" :header="t('share.uploaderTitle')" modal class="tenant-dialog share-dialog">
    <div class="form">
      <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>
      <Message v-if="message" severity="success" :closable="false">{{ message }}</Message>

      <p class="form-copy">{{ t('share.uploaderDialogCopy') }}</p>

      <label>
        {{ t('common.name') }}
        <InputText v-model="name" autocomplete="off" :placeholder="t('common.optional')" />
      </label>
      <label class="toggle-row">
        <span>
          <strong>{{ t('share.passwordProtection') }}</strong>
          <small>{{ t('share.passwordProtectionHint') }}</small>
        </span>
        <ToggleSwitch v-model="passwordEnabled" :aria-label="t('share.passwordProtectionEnable')" />
      </label>
      <label v-if="passwordEnabled">
        {{ t('common.password') }}
        <InputText v-model="password" type="password" autocomplete="new-password" required />
      </label>
      <Button
        type="button"
        :label="t('share.uploaderCreate')"
        icon="pi pi-user-plus"
        :loading="loading"
        :disabled="!canCreateShare"
        @click="createUploaderShare"
      />

      <ManagementList>
        <div v-for="share in shares" :key="share.id" class="management-row">
          <div class="management-row-main">
            <strong class="management-row-title">{{ shareLabel(share) }}</strong>
            <span class="management-row-subtitle">{{ origin }}{{ share.shareUrl }}</span>
            <div class="management-row-meta">
              <span class="management-badge" :class="share.hasPassword ? 'success' : 'muted'">
                {{ share.hasPassword ? t('share.hasPassword') : t('share.noPassword') }}
              </span>
              <span class="management-badge" :class="share.enabled ? 'success' : 'warning'">
                {{ share.enabled ? t('common.active') : t('common.disabled') }}
              </span>
            </div>
          </div>
          <div class="management-actions">
            <Button
              icon="pi pi-copy"
              severity="secondary"
              outlined
              size="small"
              :aria-label="t('share.linkCopy')"
              :title="t('share.linkCopy')"
              @click="copyShare(share)"
            />
            <Button
              icon="pi pi-pencil"
              severity="secondary"
              outlined
              size="small"
              :aria-label="t('share.rename')"
              :title="t('share.rename')"
              @click="openRenameDialog(share)"
            />
            <Button
              :icon="share.enabled ? 'pi pi-lock' : 'pi pi-lock-open'"
              severity="secondary"
              outlined
              size="small"
              :aria-label="share.enabled ? t('share.toggleDisable') : t('share.toggleEnable')"
              :title="share.enabled ? t('share.toggleDisable') : t('share.toggleEnable')"
              @click="toggleShare(share)"
            />
            <Button
              icon="pi pi-key"
              severity="secondary"
              outlined
              size="small"
              :aria-label="t('share.passwordChange')"
              :title="t('share.passwordChange')"
              @click="openPasswordDialog(share)"
            />
          </div>
        </div>
      </ManagementList>
    </div>
  </Dialog>

  <Dialog v-model:visible="renameDialogVisible" :header="t('share.rename')" modal class="tenant-dialog">
    <form class="form" @submit.prevent="saveName">
      <label>
        {{ t('common.name') }}
        <InputText v-model="editName" autocomplete="off" :placeholder="t('common.optional')" />
      </label>
      <div class="button-row">
        <Button type="submit" :label="t('common.save')" icon="pi pi-save" :loading="loading" />
        <Button
          type="button"
          :label="t('common.cancel')"
          severity="secondary"
          outlined
          @click="renameDialogVisible = false"
        />
      </div>
    </form>
  </Dialog>

  <Dialog v-model:visible="passwordDialogVisible" :header="t('share.passwordProtection')" modal class="tenant-dialog">
    <form class="form" @submit.prevent="savePassword">
      <label class="toggle-row">
        <span>
          <strong>{{ t('share.passwordProtection') }}</strong>
          <small>{{ t('share.passwordProtectionHint') }}</small>
        </span>
        <ToggleSwitch v-model="editPasswordEnabled" :aria-label="t('share.passwordProtectionEnable')" />
      </label>
      <label v-if="editPasswordEnabled">
        {{ t('auth.newPassword') }}
        <InputText v-model="editPassword" type="password" autocomplete="new-password" required />
      </label>
      <div class="dialog-action-row">
        <div class="dialog-action-group">
          <Button
            type="button"
            :label="t('common.cancel')"
            severity="secondary"
            text
            @click="passwordDialogVisible = false"
          />
          <Button
            type="submit"
            :label="t('common.save')"
            icon="pi pi-save"
            :loading="loading"
            :disabled="!canSavePassword"
          />
        </div>
      </div>
    </form>
  </Dialog>
</template>
