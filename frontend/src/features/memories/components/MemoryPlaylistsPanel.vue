<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import InputText from 'primevue/inputtext'
import Textarea from 'primevue/textarea'
import ConfirmationDialog from '../../../shared/components/ConfirmationDialog.vue'
import type {
  CreateMemoryPlaylistRequest,
  MemoryPlaylist,
  UpdateMemoryPlaylistRequest
} from '../api'
import type { MemoryPlaylistProvider } from '../../../api/generated'
import { getErrorMessage } from '../../../shared/utils/errors'

const props = defineProps<{
  playlists: MemoryPlaylist[]
  canEdit?: boolean
  createPlaylist?: (request: CreateMemoryPlaylistRequest) => Promise<MemoryPlaylist>
  updatePlaylist?: (playlistId: string, request: UpdateMemoryPlaylistRequest) => Promise<MemoryPlaylist>
  deletePlaylist?: (playlistId: string) => Promise<void>
}>()

const emit = defineEmits<{
  changed: []
}>()

const { t } = useI18n()
const addOpen = ref(false)
const editPlaylist = ref<MemoryPlaylist | null>(null)
const deleteCandidate = ref<MemoryPlaylist | null>(null)
const title = ref('')
const note = ref('')
const url = ref('')
const saving = ref(false)
const error = ref('')

const playlistCountLabel = computed(() => t('playlist.count', { count: props.playlists.length }))
const supportedProvidersLabel = computed(() => t('playlist.supportedProviders'))

function providerLabel(provider: MemoryPlaylistProvider): string {
  return t(`playlist.provider.${provider}`)
}

function openAdd() {
  title.value = ''
  note.value = ''
  url.value = ''
  error.value = ''
  addOpen.value = true
}

function openEdit(playlist: MemoryPlaylist) {
  editPlaylist.value = playlist
  title.value = playlist.title
  note.value = playlist.note ?? ''
  url.value = playlist.url
  error.value = ''
}

async function submitAdd() {
  if (!props.createPlaylist) return
  saving.value = true
  error.value = ''
  try {
    await props.createPlaylist({ title: title.value || null, note: note.value || null, url: url.value })
    addOpen.value = false
    emit('changed')
  } catch (unknownError) {
    error.value = getErrorMessage(unknownError, t('playlist.saveFailed'))
  } finally {
    saving.value = false
  }
}

async function submitEdit() {
  if (!props.updatePlaylist || !editPlaylist.value) return
  saving.value = true
  error.value = ''
  try {
    await props.updatePlaylist(editPlaylist.value.id, {
      title: title.value,
      note: note.value || undefined,
      url: url.value
    })
    editPlaylist.value = null
    emit('changed')
  } catch (unknownError) {
    error.value = getErrorMessage(unknownError, t('playlist.saveFailed'))
  } finally {
    saving.value = false
  }
}

async function markFeatured(playlist: MemoryPlaylist) {
  if (!props.updatePlaylist || playlist.featured) return
  saving.value = true
  error.value = ''
  try {
    await props.updatePlaylist(playlist.id, {
      title: playlist.title,
      note: playlist.note ?? undefined,
      featured: true
    })
    emit('changed')
  } catch (unknownError) {
    error.value = getErrorMessage(unknownError, t('playlist.saveFailed'))
  } finally {
    saving.value = false
  }
}

async function confirmDelete() {
  if (saving.value || !props.deletePlaylist || !deleteCandidate.value) return
  saving.value = true
  error.value = ''
  try {
    await props.deletePlaylist(deleteCandidate.value.id)
    deleteCandidate.value = null
    emit('changed')
  } catch (unknownError) {
    error.value = getErrorMessage(unknownError, t('playlist.deleteFailed'))
  } finally {
    saving.value = false
  }
}

</script>

<template>
  <section class="memory-playlists">
    <div class="memory-playlists-toolbar">
      <div>
        <h2>{{ t('playlist.title') }}</h2>
        <p>{{ playlistCountLabel }}</p>
      </div>
      <Button
        v-if="canEdit"
        icon="pi pi-plus"
        :label="t('playlist.add')"
        size="small"
        @click="openAdd"
      />
    </div>

    <p v-if="error" class="memory-form-error">{{ error }}</p>

    <div v-if="playlists.length === 0" class="veyq-empty veyq-empty-action">
      <span class="pi pi-volume-up" />
      <span>{{ t('playlist.empty') }}</span>
      <small>{{ supportedProvidersLabel }}</small>
      <Button
        v-if="canEdit"
        icon="pi pi-plus"
        :label="t('playlist.add')"
        size="small"
        @click="openAdd"
      />
    </div>

    <div v-else class="memory-playlist-grid">
      <article v-for="playlist in playlists" :key="playlist.id" class="veyq-card memory-playlist-card">
        <div class="memory-playlist-card-header">
          <div>
            <p>{{ providerLabel(playlist.provider) }}</p>
            <h3>{{ playlist.title }}</h3>
            <span v-if="playlist.note">{{ playlist.note }}</span>
          </div>
        </div>

        <div class="memory-track-actions">
          <Button
            v-if="canEdit && !playlist.featured"
            icon="pi pi-headphones"
            :label="t('playlist.setFeatured')"
            size="small"
            severity="secondary"
            outlined
            :disabled="saving"
            @click="markFeatured(playlist)"
          />
          <span v-else-if="playlist.featured" class="memory-featured-playlist-badge">
            <i class="pi pi-headphones" />
            {{ t('playlist.featured') }}
          </span>
          <a :href="playlist.url" class="p-button p-button-sm p-button-secondary p-button-outlined" target="_blank" rel="noreferrer">
            <span class="pi pi-external-link" />
            <span>{{ t('playlist.openProvider') }}</span>
          </a>
          <Button
            v-if="canEdit"
            icon="pi pi-pencil"
            :label="t('common.edit')"
            size="small"
            severity="secondary"
            outlined
            @click="openEdit(playlist)"
          />
          <Button
            v-if="canEdit"
            icon="pi pi-trash"
            :label="t('common.delete')"
            size="small"
            severity="danger"
            outlined
            @click="deleteCandidate = playlist"
          />
        </div>
      </article>
    </div>

    <Dialog v-model:visible="addOpen" modal :header="t('playlist.add')" class="memory-track-dialog">
      <div class="veyq-form memory-dialog-form">
        <label>
          <span>{{ t('playlist.url') }}</span>
          <InputText v-model="url" autocomplete="off" />
          <small>{{ supportedProvidersLabel }}</small>
        </label>
        <label>
          <span>{{ t('playlist.nameOptional') }}</span>
          <InputText v-model="title" autocomplete="off" />
        </label>
        <label>
          <span>{{ t('playlist.note') }}</span>
          <Textarea v-model="note" rows="3" auto-resize />
        </label>
        <div class="veyq-dialog-action-row">
          <Button icon="pi pi-times" :label="t('common.cancel')" severity="secondary" outlined :disabled="saving" @click="addOpen = false" />
          <Button icon="pi pi-save" :label="t('common.save')" :loading="saving" :disabled="!url.trim()" @click="submitAdd" />
        </div>
      </div>
    </Dialog>

    <Dialog :visible="Boolean(editPlaylist)" modal :header="t('playlist.edit')" class="memory-track-dialog" @update:visible="editPlaylist = null">
      <div class="veyq-form memory-dialog-form">
        <label>
          <span>{{ t('playlist.name') }}</span>
          <InputText v-model="title" autocomplete="off" />
        </label>
        <label>
          <span>{{ t('playlist.note') }}</span>
          <Textarea v-model="note" rows="3" auto-resize />
        </label>
        <label>
          <span>{{ t('playlist.url') }}</span>
          <InputText v-model="url" autocomplete="off" />
          <small>{{ supportedProvidersLabel }}</small>
        </label>
        <div class="veyq-dialog-action-row">
          <Button icon="pi pi-times" :label="t('common.cancel')" severity="secondary" outlined :disabled="saving" @click="editPlaylist = null" />
          <Button icon="pi pi-save" :label="t('common.save')" :loading="saving" :disabled="!title.trim() || !url.trim()" @click="submitEdit" />
        </div>
      </div>
    </Dialog>

    <ConfirmationDialog
      :visible="Boolean(deleteCandidate)"
      :title="t('playlist.delete')"
      :confirm-label="t('common.delete')"
      confirm-icon="pi pi-trash"
      confirm-severity="danger"
      dialog-class="memory-track-dialog"
      form-class="memory-dialog-form"
      :loading="saving"
      @update:visible="(nextVisible) => { if (!nextVisible) deleteCandidate = null }"
      @confirm="confirmDelete"
    >
      <template #default>
        <p>{{ t('playlist.deleteConfirm', { name: deleteCandidate?.title }) }}</p>
      </template>
    </ConfirmationDialog>
  </section>
</template>
