<script setup lang="ts">
import { ref, watch } from 'vue'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import InputNumber from 'primevue/inputnumber'
import Message from 'primevue/message'
import {
  getLibraryShares,
  shareLibraryItemWithGroup,
  shareLibraryItemWithUser,
  unshareLibraryItemWithGroup,
  unshareLibraryItemWithUser,
  type ExplorerAlbum,
  type ExplorerFolder,
  type LibraryItemType,
  type LibraryShares
} from '../../api/explorerClient'
import { getErrorMessage } from '../../shared/utils/errors'

const visible = defineModel<boolean>('visible', { required: true })
const props = defineProps<{
  tenantName: string
  type: LibraryItemType
  item: ExplorerFolder | ExplorerAlbum | null
}>()

const shares = ref<LibraryShares>({ users: [], groups: [] })
const userId = ref<number | null>(null)
const groupId = ref<number | null>(null)
const error = ref('')
const loading = ref(false)

watch(visible, (isVisible) => {
  if (isVisible) void loadShares()
})

async function loadShares() {
  if (!props.item) return
  error.value = ''
  loading.value = true
  try {
    shares.value = await getLibraryShares(props.tenantName, props.type, props.item.path)
  } catch (unknownError) {
    error.value = getErrorMessage(unknownError, 'Freigaben konnten nicht geladen werden')
  } finally {
    loading.value = false
  }
}

async function addUserShare() {
  if (!props.item || !userId.value) return
  await updateShares(() =>
    shareLibraryItemWithUser(props.tenantName, props.type, props.item!.path, userId.value!)
  )
  userId.value = null
}

async function addGroupShare() {
  if (!props.item || !groupId.value) return
  await updateShares(() =>
    shareLibraryItemWithGroup(props.tenantName, props.type, props.item!.path, groupId.value!)
  )
  groupId.value = null
}

async function removeUserShare(id: number) {
  if (!props.item) return
  await updateShares(() =>
    unshareLibraryItemWithUser(props.tenantName, props.type, props.item!.path, id)
  )
}

async function removeGroupShare(id: number) {
  if (!props.item) return
  await updateShares(() =>
    unshareLibraryItemWithGroup(props.tenantName, props.type, props.item!.path, id)
  )
}

async function updateShares(operation: () => Promise<LibraryShares>) {
  error.value = ''
  loading.value = true
  try {
    shares.value = await operation()
  } catch (unknownError) {
    error.value = getErrorMessage(unknownError, 'Freigabe konnte nicht gespeichert werden')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <Dialog v-model:visible="visible" header="Freigaben" modal class="share-dialog">
    <div class="form">
      <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>

      <section class="share-section">
        <h3>User</h3>
        <div class="inline-form compact">
          <InputNumber
            v-model="userId"
            input-id="share-user-id"
            placeholder="User-ID"
            :use-grouping="false"
          />
          <Button
            label="User freigeben"
            icon="pi pi-user-plus"
            :loading="loading"
            @click="addUserShare"
          />
        </div>
        <div class="tenant-list">
          <div v-for="user in shares.users" :key="user.userId" class="tenant-row">
            <span>{{ user.displayName || user.email }}</span>
            <Button
              icon="pi pi-times"
              text
              rounded
              severity="danger"
              aria-label="Freigabe entfernen"
              @click="removeUserShare(user.userId)"
            />
          </div>
        </div>
      </section>

      <section class="share-section">
        <h3>Gruppen</h3>
        <div class="inline-form compact">
          <InputNumber
            v-model="groupId"
            input-id="share-group-id"
            placeholder="Gruppen-ID"
            :use-grouping="false"
          />
          <Button
            label="Gruppe freigeben"
            icon="pi pi-users"
            :loading="loading"
            @click="addGroupShare"
          />
        </div>
        <div class="tenant-list">
          <div v-for="group in shares.groups" :key="group.groupId" class="tenant-row">
            <span>{{ group.displayName || group.name }}</span>
            <Button
              icon="pi pi-times"
              text
              rounded
              severity="danger"
              aria-label="Freigabe entfernen"
              @click="removeGroupShare(group.groupId)"
            />
          </div>
        </div>
      </section>

      <div class="button-row">
        <Button
          type="button"
          label="Schließen"
          severity="secondary"
          outlined
          @click="visible = false"
        />
      </div>
    </div>
  </Dialog>
</template>
