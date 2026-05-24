<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import ProgressSpinner from 'primevue/progressspinner'
import CreateMemoryDialog from '../features/memories/components/CreateMemoryDialog.vue'
import MemoryDetail from '../features/memories/components/MemoryDetail.vue'
import { getErrorMessage } from '../shared/utils/errors'
import {
  createPublicShareChildMemory,
  deletePublicShareMedia,
  deletePublicShareMemory,
  getPublicShare,
  getPublicShareMemoryByPath,
  listPublicShareChildMemories,
  listPublicShareMemoryMedia,
  loginPublicShare,
  logoutPublicShare,
  removePublicShareMemoryCover,
  setPublicShareMemoryCover,
  updatePublicShareMediaMetadata,
  updatePublicShareMemory,
  uploadPublicShareMedia,
  uploadPublicShareMemoryMedia,
  type Memory,
  type MemoryMedia,
  type PublicShareSummary
} from '../features/memories/api'

const route = useRoute()
const router = useRouter()
const { t } = useI18n()

const shareId = computed(() => String(route.params.shareId || ''))
const sharePath = computed(() => routePathToSharePath(route.params.pathMatch))
const share = ref<PublicShareSummary | null>(null)
const memory = ref<Memory | null>(null)
const childMemories = ref<Memory[]>([])
const media = ref<MemoryMedia[]>([])
const password = ref('')
const displayName = ref('')
const selectedFile = ref<File | null>(null)
const createMemoryOpen = ref(false)
const error = ref('')
const loading = ref(false)

const isUploaderOnly = computed(() => share.value?.role === 'UPLOADER')
const hasShareAccess = computed(() => Boolean(share.value?.authenticated || share.value?.canViewGallery))
const canShowLogin = computed(() =>
  Boolean(share.value && !share.value.authenticated && (share.value.passwordRequired || share.value.displayNameRequired))
)
const canSubmitLogin = computed(() =>
  Boolean(
    share.value &&
      (!share.value.passwordRequired || password.value.trim()) &&
      (!share.value.displayNameRequired || displayName.value.trim())
  )
)
const canShowMemoryDetail = computed(() => Boolean(hasShareAccess.value && memory.value && !isUploaderOnly.value))
const capabilities = computed(() => ({
  canEdit: Boolean(share.value?.canEdit),
  canUpload: Boolean(share.value?.canUpload),
  canCreateChildMemory: Boolean(share.value?.canEdit),
  canDelete: Boolean(share.value?.canDelete),
  canShare: false,
  canManageCover: Boolean(share.value?.canEdit),
  canEditMediaMetadata: Boolean(share.value?.canEdit),
  canDeleteMedia: Boolean(share.value?.canDelete),
  canDeleteCurrentMemory: Boolean(share.value?.canDelete && memory.value?.id !== share.value.rootMemory?.id),
  showScope: false
}))

onMounted(loadShare)

watch(
  () => route.fullPath,
  () => {
    if (hasShareAccess.value && !isUploaderOnly.value) void loadCurrentMemory()
  }
)

async function loadShare() {
  error.value = ''
  loading.value = true
  try {
    share.value = await getPublicShare(shareId.value)
    if (share.value.redirectUrl) {
      await router.replace(share.value.redirectUrl)
      return
    }
    if (hasShareAccess.value) await loadProtectedContent()
  } catch (unknownError) {
    error.value = getErrorMessage(unknownError, t('share.loadFailed'))
  } finally {
    loading.value = false
  }
}

async function login() {
  if (!canSubmitLogin.value) return
  error.value = ''
  loading.value = true
  try {
    share.value = await loginPublicShare(
      shareId.value,
      share.value?.passwordRequired ? password.value : null,
      displayName.value.trim()
    )
    password.value = ''
    if (share.value.redirectUrl) {
      await router.replace(share.value.redirectUrl)
      return
    }
    await loadProtectedContent()
  } catch (unknownError) {
    error.value = getErrorMessage(unknownError, t('share.loginFailed'))
  } finally {
    loading.value = false
  }
}

async function closeShare() {
  await logoutPublicShare(shareId.value)
  memory.value = null
  childMemories.value = []
  media.value = []
  await loadShare()
}

async function loadProtectedContent() {
  if (isUploaderOnly.value) return
  await loadCurrentMemory()
}

async function loadCurrentMemory() {
  error.value = ''
  loading.value = true
  try {
    const current = await getPublicShareMemoryByPath(shareId.value, sharePath.value)
    memory.value = current
    childMemories.value = await listPublicShareChildMemories(shareId.value, current.id)
    media.value = await listPublicShareMemoryMedia(shareId.value, current.id)
  } catch (unknownError) {
    error.value = getErrorMessage(unknownError, t('memory.loadFailed'))
  } finally {
    loading.value = false
  }
}

function openMemory(nextMemory: Memory) {
  void router.push(publicShareRoute(nextMemory.path))
}

function handleMemoryChanged(updated: Memory) {
  if (updated.path !== sharePath.value) {
    void router.replace(publicShareRoute(updated.path))
    return
  }
  void loadCurrentMemory()
}

function handleMemoryDeleted(parentPath: string) {
  void router.replace(publicShareRoute(parentPath))
}

function selectUpload(event: Event) {
  selectedFile.value = (event.target as HTMLInputElement).files?.[0] || null
}

async function uploadToRoot() {
  if (!selectedFile.value) return
  error.value = ''
  loading.value = true
  try {
    await uploadPublicShareMedia(shareId.value, {
      file: selectedFile.value,
      title: selectedFile.value.name
    })
    selectedFile.value = null
  } catch (unknownError) {
    error.value = getErrorMessage(unknownError, t('share.uploadFailed'))
  } finally {
    loading.value = false
  }
}

function publicShareRoute(path: string) {
  const normalized = path === '/' ? '' : path.replace(/^\/+/, '/')
  return `/s/${shareId.value}${normalized}`
}

function routePathToSharePath(value: unknown): string {
  if (Array.isArray(value)) return value.length ? `/${value.join('/')}` : '/'
  if (typeof value === 'string' && value) return `/${value}`
  return '/'
}
</script>

<template>
  <section class="public-share-view">
    <header class="public-share-header">
      <div>
        <p>Veyq Share</p>
        <h1>{{ memory?.displayName || share?.rootMemory?.displayName || 'Memory Share' }}</h1>
      </div>
      <Button
        v-if="share?.authenticated"
        :label="t('common.close')"
        icon="pi pi-sign-out"
        severity="secondary"
        outlined
        @click="closeShare"
      />
    </header>

    <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>

    <form v-if="canShowLogin" class="public-share-login" @submit.prevent="login">
      <InputText
        v-if="share?.passwordRequired"
        v-model="password"
        type="password"
        autocomplete="current-password"
        :placeholder="t('common.password')"
      />
      <InputText
        v-if="share?.displayNameRequired"
        v-model="displayName"
        autocomplete="name"
        :placeholder="t('share.displayNamePlaceholder')"
      />
      <Button
        :label="t('share.open')"
        icon="pi pi-lock-open"
        type="submit"
        :loading="loading"
        :disabled="!canSubmitLogin"
      />
    </form>

    <div v-else-if="loading" class="memory-loading">
      <ProgressSpinner class="small-spinner" />
      <span>{{ t('share.loading') }}</span>
    </div>

    <form
      v-else-if="share?.authenticated && isUploaderOnly"
      class="public-share-upload"
      @submit.prevent="uploadToRoot"
    >
      <input type="file" accept="image/*,video/*" @change="selectUpload" />
      <Button
        :label="t('common.upload')"
        icon="pi pi-upload"
        type="submit"
        :loading="loading"
        :disabled="!selectedFile"
      />
    </form>

    <MemoryDetail
      v-else-if="canShowMemoryDetail && memory"
      tenant-name=""
      :memory="memory"
      :child-memories="childMemories"
      :media="media"
      :capabilities="capabilities"
      :save-memory="(memoryId, request) => updatePublicShareMemory(shareId, memoryId, request)"
      :set-cover="(memoryId, request) => setPublicShareMemoryCover(shareId, memoryId, request)"
      :remove-cover="(memoryId) => removePublicShareMemoryCover(shareId, memoryId)"
      :upload-media="(memoryId, request) => uploadPublicShareMemoryMedia(shareId, memoryId, request)"
      :delete-memory="(memoryId) => deletePublicShareMemory(shareId, memoryId)"
      :update-media-metadata="(mediaId, memoryId, request) => updatePublicShareMediaMetadata(shareId, memoryId, mediaId, request)"
      :delete-media="(mediaId, memoryId) => deletePublicShareMedia(shareId, memoryId, mediaId)"
      @open-memory="openMemory"
      @create-child-memory="createMemoryOpen = true"
      @changed="handleMemoryChanged"
      @deleted="handleMemoryDeleted"
      @media-deleted="loadCurrentMemory"
    />

    <CreateMemoryDialog
      v-if="memory"
      v-model:visible="createMemoryOpen"
      tenant-name=""
      tenant-display-name="Share"
      :parent-memory-id="memory.id"
      :create-memory-override="(request) => createPublicShareChildMemory(shareId, memory!.id, request)"
      :set-cover-override="(memoryId, request) => setPublicShareMemoryCover(shareId, memoryId, request)"
      @saved="loadCurrentMemory"
    />
  </section>
</template>
