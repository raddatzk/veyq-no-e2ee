<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import Select from 'primevue/select'
import ConfirmationDialog from '../../../shared/components/ConfirmationDialog.vue'
import ManagementList from '../../../shared/components/ManagementList.vue'
import {
  createGroup,
  deleteGroup as deleteGroupApi,
  updateGroup,
  type GroupMemberSummary,
  type GroupRole,
  type GroupSummary
} from '../../../api/groupClient'
import { toPermalink } from '../../../composables/usePermalink'
import { getErrorMessage, isApiStatus } from '../../../shared/utils/errors'
import { getTenantUsers, type TenantUserSummary } from '../api'

const props = defineProps<{
  tenantName: string
}>()

const visible = defineModel<boolean>('visible', { default: false })
const emit = defineEmits<{
  saved: []
}>()

const { t } = useI18n()
const tenantUsers = ref<TenantUserSummary[]>([])
const selectedGroup = ref<GroupSummary | null>(null)
const saving = ref(false)
const error = ref('')
const message = ref('')
const createDialogVisible = ref(false)
const detailDialogVisible = ref(false)
const deleteDialogVisible = ref(false)
const deleteConfirmation = ref('')
const createDisplayName = ref('')
const createName = ref('')
const createPermalinkTouched = ref(false)
const displayName = ref('')
const name = ref('')
const permalinkTouched = ref(false)
const selectedUserId = ref<number | null>(null)
const selectedRole = ref<GroupRole>('GroupMember')
const editingInitialGroupName = ref('')

const roleOptions = computed(() => [
  { label: t('group.roles.GroupOwner'), value: 'GroupOwner' },
  { label: t('group.roles.GroupMember'), value: 'GroupMember' }
])
const selectedMembers = computed(() => selectedGroup.value?.members || [])
const availableTenantUsers = computed(() => {
  const memberIds = new Set(selectedMembers.value.map((member) => member.user.userId))
  return tenantUsers.value
    .filter((user) => !memberIds.has(user.user.userId) && user.status === 'Active')
    .map((user) => ({
      label: user.email ? `${user.user.displayName} (${user.email})` : user.user.displayName,
      value: user.user.userId
    }))
})
const ownerCount = computed(
  () => selectedMembers.value.filter((member) => member.role === 'GroupOwner').length
)
const canConfirmDeleteGroup = computed(
  () =>
    Boolean(selectedGroup.value) &&
    deleteConfirmation.value.trim() === selectedGroup.value!.displayName.trim() &&
    !saving.value
)

watch(
  visible,
  (nextVisible) => {
    if (nextVisible) openCreateDialog()
    else closeAllDialogs()
  },
  { immediate: true }
)

watch(createDisplayName, (value) => {
  if (!createPermalinkTouched.value) createName.value = toPermalink(value)
})

watch(displayName, (value) => {
  if (!permalinkTouched.value) name.value = toPermalink(value)
})

function openCreateDialog() {
  resetCreateForm()
  resetEditor()
  tenantUsers.value = []
  selectedGroup.value = null
  error.value = ''
  message.value = ''
  createDialogVisible.value = true
  detailDialogVisible.value = false
  deleteDialogVisible.value = false
}

function closeAllDialogs() {
  createDialogVisible.value = false
  detailDialogVisible.value = false
  deleteDialogVisible.value = false
}

function closeFlow() {
  closeAllDialogs()
  visible.value = false
}

function updateCreateDialogVisible(nextVisible: boolean) {
  if (nextVisible) {
    createDialogVisible.value = true
  } else if (!detailDialogVisible.value) {
    closeFlow()
  } else {
    createDialogVisible.value = false
  }
}

function updateDetailDialogVisible(nextVisible: boolean) {
  if (nextVisible) {
    detailDialogVisible.value = true
  } else {
    closeFlow()
  }
}

function resetCreateForm() {
  createDisplayName.value = ''
  createName.value = ''
  createPermalinkTouched.value = false
}

function resetEditor() {
  displayName.value = ''
  name.value = ''
  permalinkTouched.value = false
  editingInitialGroupName.value = ''
  selectedUserId.value = null
  selectedRole.value = 'GroupMember'
  deleteConfirmation.value = ''
}

function updateCreatePermalink(value: string | undefined) {
  createPermalinkTouched.value = true
  createName.value = toPermalink(value || '')
}

function updatePermalink(value: string | undefined) {
  permalinkTouched.value = true
  name.value = toPermalink(value || '')
}

async function submitCreate() {
  const groupName = createName.value.trim()
  const groupDisplayName = createDisplayName.value.trim()
  if (!props.tenantName || !groupName || !groupDisplayName) return

  saving.value = true
  error.value = ''
  message.value = ''
  try {
    const created = await createGroup(props.tenantName, groupName, groupDisplayName)
    applySelectedGroup(created)
    await loadTenantUsers()
    createDialogVisible.value = false
    detailDialogVisible.value = true
    message.value = t('group.created')
    emit('saved')
  } catch (caught) {
    if (isApiStatus(caught, 409)) {
      error.value = t('group.duplicate')
    } else {
      error.value = getErrorMessage(caught, t('group.createFailed'))
    }
  } finally {
    saving.value = false
  }
}

async function saveGroup() {
  if (!selectedGroup.value) return
  const groupName = name.value.trim()
  const groupDisplayName = displayName.value.trim()
  if (!props.tenantName || !groupName || !groupDisplayName) return

  saving.value = true
  error.value = ''
  message.value = ''
  try {
    const saved = await updateGroup(props.tenantName, editingInitialGroupName.value, {
      name: groupName,
      displayName: groupDisplayName,
      members: new Set(
        selectedGroup.value.members.map((member) => ({
          id: member.user.userId,
          role: member.role || 'GroupMember'
        }))
      )
    })
    applySelectedGroup(saved)
    message.value = t('group.saved')
    emit('saved')
  } catch (caught) {
    error.value = getErrorMessage(caught, t('group.saveFailed'))
  } finally {
    saving.value = false
  }
}

function addMember() {
  if (!selectedGroup.value || selectedUserId.value === null) return
  const userId = Number(selectedUserId.value)
  const user = tenantUsers.value.find((tenantUser) => tenantUser.user.userId === userId)
  if (!user) return

  selectedGroup.value = {
    ...selectedGroup.value,
    members: [
      ...selectedGroup.value.members,
      {
        user: {
          userId: user.user.userId,
          displayName: user.user.displayName,
          permalink: user.user.permalink
        },
        role: selectedRole.value
      }
    ].sort((left, right) => left.user.displayName.localeCompare(right.user.displayName))
  }
  selectedUserId.value = null
  selectedRole.value = 'GroupMember'
}

function changeRole(member: GroupMemberSummary, role: GroupRole) {
  if (!selectedGroup.value) return
  if (member.role === 'GroupOwner' && role !== 'GroupOwner' && ownerCount.value <= 1) {
    error.value = t('group.ownerRequired')
    return
  }
  selectedGroup.value = {
    ...selectedGroup.value,
    members: selectedGroup.value.members.map((item) =>
      item.user.userId === member.user.userId ? { ...item, role } : item
    )
  }
}

function removeMember(member: GroupMemberSummary) {
  if (!selectedGroup.value) return
  if (member.role === 'GroupOwner' && ownerCount.value <= 1) {
    error.value = t('group.ownerRequired')
    return
  }
  selectedGroup.value = {
    ...selectedGroup.value,
    members: selectedGroup.value.members.filter((item) => item.user.userId !== member.user.userId)
  }
}

function openDeleteDialog() {
  deleteConfirmation.value = ''
  error.value = ''
  message.value = ''
  deleteDialogVisible.value = true
}

async function submitDeleteGroup() {
  if (saving.value || !props.tenantName || !selectedGroup.value || !canConfirmDeleteGroup.value) return
  saving.value = true
  error.value = ''
  message.value = ''
  try {
    await deleteGroupApi(props.tenantName, selectedGroup.value.name)
    emit('saved')
    closeFlow()
  } catch (caught) {
    error.value = getErrorMessage(caught, t('group.deleteFailed'))
  } finally {
    saving.value = false
  }
}

function applySelectedGroup(group: GroupSummary) {
  selectedGroup.value = group
  editingInitialGroupName.value = group.name
  displayName.value = group.displayName
  name.value = group.name
  permalinkTouched.value = false
  selectedUserId.value = null
  selectedRole.value = 'GroupMember'
}

async function loadTenantUsers() {
  if (tenantUsers.value.length > 0 || !props.tenantName) return
  tenantUsers.value = await getTenantUsers(props.tenantName)
}
</script>

<template>
  <Dialog
    :visible="createDialogVisible"
    :header="t('group.create')"
    modal
    class="tenant-dialog"
    @update:visible="updateCreateDialogVisible"
  >
    <form class="veyq-form" @submit.prevent="submitCreate">
      <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>
      <label>
        {{ t('common.name') }}
        <InputText v-model="createDisplayName" autocomplete="off" required />
      </label>
      <label>
        {{ t('common.permalink') }}
        <InputText
          :model-value="createName"
          autocomplete="off"
          required
          @update:model-value="updateCreatePermalink"
        />
      </label>
      <div class="veyq-dialog-action-row">
        <Button
          type="button"
          :label="t('common.cancel')"
          icon="pi pi-times"
          severity="secondary"
          outlined
          @click="closeFlow"
        />
        <Button type="submit" :label="t('common.create')" icon="pi pi-plus" :loading="saving" />
      </div>
    </form>
  </Dialog>

  <Dialog
    :visible="detailDialogVisible"
    :header="selectedGroup ? t('group.manageNamed', { name: selectedGroup.displayName }) : t('group.manage')"
    modal
    class="tenant-dialog group-detail-dialog"
    @update:visible="updateDetailDialogVisible"
  >
    <form
      v-if="selectedGroup"
      class="settings-form group-detail-form"
      @submit.prevent="saveGroup"
    >
      <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>
      <Message v-if="message" severity="success" :closable="false">{{ message }}</Message>

      <label>
        <span>{{ t('common.name') }}</span>
        <InputText v-model="displayName" autocomplete="off" required />
      </label>
      <label>
        <span>{{ t('common.permalink') }}</span>
        <InputText
          :model-value="name"
          autocomplete="off"
          required
          @update:model-value="updatePermalink"
        />
      </label>

      <ManagementList>
        <div
          v-for="member in selectedGroup.members"
          :key="member.user.userId"
          class="veyq-list-row"
        >
          <div class="veyq-row-main">
            <strong class="veyq-row-title">{{ member.user.displayName }}</strong>
          </div>
          <Select
            class="management-control"
            :model-value="member.role"
            :options="roleOptions"
            option-label="label"
            option-value="value"
            :aria-label="t('group.role')"
            @update:model-value="changeRole(member, $event)"
          />
          <div class="management-actions">
            <Button
              type="button"
              icon="pi pi-times"
              severity="secondary"
              outlined
              size="small"
              :aria-label="t('group.memberRemove')"
              :title="t('group.memberRemove')"
              @click="removeMember(member)"
            />
          </div>
        </div>
      </ManagementList>

      <div class="management-add-row">
        <Select
          v-model="selectedUserId"
          :options="availableTenantUsers"
          option-label="label"
          option-value="value"
          :placeholder="t('group.memberSelect')"
        />
        <Select
          v-model="selectedRole"
          :options="roleOptions"
          option-label="label"
          option-value="value"
          :aria-label="t('group.roleNew')"
        />
        <Button
          type="button"
          :label="t('common.add')"
          icon="pi pi-plus"
          severity="secondary"
          outlined
          :disabled="selectedUserId === null"
          @click="addMember"
        />
      </div>

      <div class="veyq-button-row">
        <Button type="submit" :label="t('common.save')" icon="pi pi-save" :loading="saving" />
        <Button
          type="button"
          :label="t('group.delete')"
          icon="pi pi-trash"
          severity="danger"
          outlined
          @click="openDeleteDialog"
        />
      </div>
    </form>
  </Dialog>

  <ConfirmationDialog
    v-model:visible="deleteDialogVisible"
    :title="t('group.delete')"
    :confirm-label="t('common.delete')"
    confirm-icon="pi pi-trash"
    confirm-severity="danger"
    :confirm-disabled="!canConfirmDeleteGroup"
    :loading="saving"
    @confirm="submitDeleteGroup"
  >
    <template #default="{ disabled }">
      <template v-if="selectedGroup">
      <p>
        {{ t('tenant.deleteConfirm', { name: selectedGroup.displayName }) }}
      </p>
      <label>
        {{ t('settings.confirmationDisplayName') }}
        <InputText
          v-model="deleteConfirmation"
          autocomplete="off"
          :placeholder="selectedGroup.displayName"
          :disabled="disabled"
        />
      </label>
      </template>
    </template>
  </ConfirmationDialog>
</template>
