<script setup lang="ts">
// The share create/edit dialog (cr-0116), opened from the list-only ShareDialog. Shared by BOTH share flows via the
// `uploader` flag so they stay identical by construction: the normal flow shows a VIEWER/CONTRIBUTOR role switcher;
// the uploader (drop-box) flow fixes the role to UPLOADER and hides the switcher — that is the ONLY difference.
// CREATE collects an optional name + per-locale note, mints the share and shows the copyable link. EDIT (editShare
// set): pre-fills name + note (decrypted via the recoverable K_share) and saves them in place with `updateShare` —
// the link stays valid. Operator-blind: name + note sealed under K_share; the fresh K_share rides only the returned
// link. (The uploader-links capability is gated on the memory ACTION that opens the uploader dialog, not here.)
import { ref, computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import Dialog from 'primevue/dialog'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import SelectButton from 'primevue/selectbutton'
import LocaleMarkdownEditor from './LocaleMarkdownEditor.vue'
import { createShare, updateShare, readShareNoteMap, readShareName, type LocaleMap, type ManagedShare } from '../share'
import { ShareRole } from '../../../api/generated/sharing'

const props = defineProps<{
  nodeId: string
  memoryKey: Uint8Array
  memoryName: string
  /** When set, the dialog edits this share's name + note in place instead of creating a new one. */
  editShare?: ManagedShare | null
  /** The uploader (drop-box) variant: role fixed to UPLOADER, no switcher. Identical otherwise. */
  uploader?: boolean
}>()
const emit = defineEmits<{ close: []; saved: [] }>()
const { t } = useI18n()

const isEdit = computed(() => !!props.editShare)
// The role is chosen only when creating a normal share; an edit keeps the minted role, and the uploader variant is
// fixed to UPLOADER (no switcher).
const showRoleSwitcher = computed(() => !isEdit.value && !props.uploader)
const headerLabel = computed(() =>
  isEdit.value ? t('share.editTitle') : props.uploader ? t('share.uploaderCreate') : t('share.createNew'),
)
const name = ref('')
const role = ref<ShareRole>(props.uploader ? ShareRole.Uploader : ShareRole.Viewer)
const note = ref<LocaleMap>({})
const busy = ref(false)
const error = ref(false)
const link = ref('')
const copied = ref(false)

onMounted(() => {
  const s = props.editShare
  if (s && s.sealedShareKey) {
    name.value = readShareName(props.memoryKey, s.sealedShareKey, s.sealedShareName)
    note.value = readShareNoteMap(props.memoryKey, s.sealedShareKey, s.sealedNote)
    role.value = s.role
  }
})

// The normal flow's switcher offers VIEWER + CONTRIBUTOR only — the uploader is its own action/dialog (no switcher).
const roleOptions = computed(() => [
  { label: t('share.roleViewer'), value: ShareRole.Viewer },
  { label: t('share.roleContributor'), value: ShareRole.Contributor },
])
const roleHint = computed(() =>
  role.value === ShareRole.Contributor ? t('share.roleContributorHint') : t('share.roleViewerHint'),
)

async function onSubmit() {
  busy.value = true
  error.value = false
  try {
    if (isEdit.value && props.editShare?.sealedShareKey) {
      await updateShare(props.editShare.shareId, props.memoryKey, props.editShare.sealedShareKey, note.value, name.value)
      emit('saved')
      emit('close')
    } else {
      const minted = await createShare(props.nodeId, props.memoryKey, props.memoryName, role.value, null, note.value, name.value)
      link.value = minted.link
      emit('saved')
    }
  } catch {
    error.value = true
  } finally {
    busy.value = false
  }
}

async function onCopy() {
  try {
    await navigator.clipboard.writeText(link.value)
    copied.value = true
    setTimeout(() => (copied.value = false), 2000)
  } catch {
    /* clipboard denied — the field stays selectable */
  }
}
</script>

<template>
  <Dialog
    :visible="true"
    :header="headerLabel"
    modal
    class="share-dialog"
    @update:visible="emit('close')"
  >
    <!-- The freshly-minted link (create only, after success). -->
    <template v-if="link">
      <p class="share-created-copy">{{ t('share.linkReady') }}</p>
      <div class="share-link-row">
        <input
          class="share-link-input"
          type="text"
          :value="link"
          readonly
          @focus="($event.target as HTMLInputElement).select()"
        />
        <Button
          :label="copied ? t('share.copied') : t('share.copy')"
          :icon="copied ? 'pi pi-check' : 'pi pi-copy'"
          severity="secondary"
          @click="onCopy"
        />
      </div>
      <div class="share-create-actions">
        <Button :label="t('common.done')" icon="pi pi-check" @click="emit('close')" />
      </div>
    </template>

    <!-- The create/edit form. -->
    <template v-else>
      <div class="share-field">
        <label for="share-create-name">{{ t('share.name') }}</label>
        <InputText id="share-create-name" v-model="name" :placeholder="t('share.nameOptional')" />
      </div>

      <!-- Normal flow: pick VIEWER/CONTRIBUTOR on create (an edit keeps the role). Uploader flow: no switcher —
           just a short description of what a drop-box link does. -->
      <div v-if="showRoleSwitcher" class="share-field">
        <label>{{ t('share.roleLabel') }}</label>
        <SelectButton v-model="role" :options="roleOptions" option-label="label" option-value="value" :allow-empty="false" />
        <p class="share-hint">{{ roleHint }}</p>
      </div>
      <p v-else-if="uploader && !isEdit" class="share-hint share-uploader-desc">{{ t('share.roleUploaderHint') }}</p>

      <div class="share-field">
        <label>{{ t('share.noteLabel') }}</label>
        <LocaleMarkdownEditor v-model="note" />
        <p class="share-hint">{{ t('share.noteHint') }}</p>
      </div>

      <p v-if="error" class="share-error">{{ isEdit ? t('share.noteSaveFailed') : t('share.createFailed') }}</p>
      <div class="share-create-actions">
        <Button
          :label="busy ? (isEdit ? t('share.noteSaving') : t('share.creating')) : (isEdit ? t('share.noteSave') : t('share.create'))"
          :icon="isEdit ? 'pi pi-check' : 'pi pi-link'"
          :disabled="busy"
          @click="onSubmit"
        />
      </div>
    </template>
  </Dialog>
</template>

<style scoped>
.share-field {
  display: grid;
  gap: 0.4rem;
  margin-bottom: 1.1rem;
}
.share-field > label {
  font-size: 0.85rem;
  font-weight: 600;
}
.share-hint {
  margin: 0;
  color: var(--app-muted);
  font-size: 0.82rem;
}
/* The uploader-flow description stands in for the (absent) role switcher, so it carries the field's spacing. */
.share-uploader-desc {
  margin-bottom: 1.1rem;
}
.share-error {
  margin: 0 0 0.5rem;
  color: var(--app-danger, #e11d48);
  font-size: 0.85rem;
}
.share-create-actions {
  display: flex;
  justify-content: flex-end;
}
.share-created-copy {
  margin: 0 0 0.6rem;
  color: var(--app-muted);
  font-size: 0.9rem;
}
.share-link-row {
  display: flex;
  gap: 0.5rem;
  margin-bottom: 1rem;
}
.share-link-input {
  flex: 1 1 auto;
  min-width: 0;
  padding: 0.45rem 0.6rem;
  border: 1px solid var(--app-line);
  border-radius: 8px;
  background: var(--app-surface);
  color: var(--app-text);
  font: inherit;
}
</style>
