<script setup lang="ts">
// The curator's Share dialog (uc.sharing.share-public-viewer, cr-0100). Turns ONE personal-scope Memory into a
// read-only, anonymous, revocable public link `/s/{shareId}#k=<K_share>` (ADR 0029). Operator-blind: the fresh
// `K_share` is generated on device, wraps the Memory's `K_mem`, and rides the link fragment — the server never
// sees it. Shows the copyable link on create, plus a manage list of the Memory's active links with revoke.
import { ref, computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import Dialog from 'primevue/dialog'
import Button from 'primevue/button'
import SelectButton from 'primevue/selectbutton'
import { createShare, listSharesFor, revokeShare, reactivateShare, updateShareNote, readShareNote, recoverShareLink, type ManagedShare } from '../share'
import { ShareRole } from '../../../api/generated/sharing'
import { useCapabilities } from '../../../shared/state/useCapabilities'
import { SHARING_UPLOADER_LINKS } from '../../platform/capabilities'

const props = defineProps<{
  nodeId: string
  memoryKey: Uint8Array
  memoryName: string
}>()
const emit = defineEmits<{ close: [] }>()

const { t, locale } = useI18n()
const { hasCapability } = useCapabilities()

const shares = ref<ManagedShare[]>([])
const loadError = ref(false)
const creating = ref(false)
const createError = ref(false)
const revokeError = ref(false)
// The most recently minted link (with its fragment key) — shown once, copyable. Only ever held in memory here;
// old shares can't reconstruct their link (K_share lives only in the link, never stored), so this is create-time only.
const freshLink = ref<string | null>(null)
const copied = ref(false)
const revokingId = ref<string | null>(null)
const reactivatingId = ref<string | null>(null)
const reactivateError = ref(false)

// cr-0115: the optional curator note to attach to the NEW share, + the in-place note editor for an existing one.
const note = ref('')
const editingId = ref<string | null>(null)
const editNote = ref('')
const savingNote = ref(false)
const noteError = ref(false)

/** The current (decrypted) note on a managed share — '' when none / not readable (a pre-cr-0115 share). */
function noteFor(share: ManagedShare): string {
  return readShareNote(props.memoryKey, share.sealedShareKey, share.sealedNote)
}
function startEditNote(share: ManagedShare) {
  editingId.value = share.shareId
  editNote.value = noteFor(share)
  noteError.value = false
}
function cancelEditNote() {
  editingId.value = null
}
async function saveNote(share: ManagedShare) {
  if (!share.sealedShareKey) return
  savingNote.value = true
  noteError.value = false
  try {
    await updateShareNote(share.shareId, props.memoryKey, share.sealedShareKey, editNote.value)
    editingId.value = null
    await loadShares()
  } catch {
    noteError.value = true
  } finally {
    savingNote.value = false
  }
}

// The share role to mint: VIEWER (read-only, cr-0100), CONTRIBUTOR (view + add photos, cr-0101), or
// UPLOADER (write-only drop-box — add photos but can't see the Memory or others', cr-0102, ADR 0031).
const role = ref<ShareRole>(ShareRole.Viewer)
// The Uploader drop-box option is gated on the `sharing.uploader-links` capability (ADR 0032): when the operator
// has disabled it instance-wide, it is dropped from the choices — and the server rejects it anyway (422). The
// viewer/contributor options ride `sharing.public-links`, which gates the Share affordance itself upstream.
const roleOptions = computed(() => {
  const options: Array<{ label: string; value: ShareRole }> = [
    { label: t('share.roleViewer'), value: ShareRole.Viewer },
    { label: t('share.roleContributor'), value: ShareRole.Contributor },
  ]
  if (hasCapability(SHARING_UPLOADER_LINKS)) {
    options.push({ label: t('share.roleUploader'), value: ShareRole.Uploader })
  }
  return options
})
function roleLabel(r: ShareRole | string): string {
  if (r === ShareRole.Uploader) return t('share.roleUploader')
  if (r === ShareRole.Contributor) return t('share.roleContributor')
  return t('share.roleViewer')
}
function roleHintFor(r: ShareRole): string {
  if (r === ShareRole.Uploader) return t('share.roleUploaderHint')
  if (r === ShareRole.Contributor) return t('share.roleContributorHint')
  return t('share.roleViewerHint')
}
const roleHint = computed(() => roleHintFor(role.value))

async function loadShares() {
  loadError.value = false
  try {
    shares.value = await listSharesFor(props.nodeId)
  } catch {
    loadError.value = true
  }
}
onMounted(loadShares)

async function onCreate() {
  creating.value = true
  createError.value = false
  copied.value = false
  try {
    const minted = await createShare(props.nodeId, props.memoryKey, props.memoryName, role.value, null, note.value)
    freshLink.value = minted.link
    note.value = '' // consumed into the new share; the manage list now edits it
    await loadShares()
  } catch {
    createError.value = true
  } finally {
    creating.value = false
  }
}

async function onCopy() {
  if (!freshLink.value) return
  try {
    await navigator.clipboard.writeText(freshLink.value)
    copied.value = true
    setTimeout(() => (copied.value = false), 2000)
  } catch {
    /* clipboard denied — the link stays selectable in the field */
  }
}

async function onRevoke(shareId: string) {
  revokingId.value = shareId
  revokeError.value = false
  try {
    await revokeShare(shareId)
    if (freshLink.value?.includes(`/s/${shareId}#`)) freshLink.value = null // the just-shown link was revoked
    await loadShares()
  } catch {
    revokeError.value = true
  } finally {
    revokingId.value = null
  }
}

// cr-0104: re-activate an orphaned (disabled) share on a shared Memory — any member may adopt it; ownership
// transfers to the caller and the guest link resolves again.
async function onReactivate(shareId: string) {
  reactivatingId.value = shareId
  reactivateError.value = false
  try {
    await reactivateShare(shareId)
    await loadShares()
  } catch {
    reactivateError.value = true
  } finally {
    reactivatingId.value = null
  }
}

function fmtDate(d: Date): string {
  return new Intl.DateTimeFormat(locale.value === 'de' ? 'de-DE' : 'en-US', { dateStyle: 'medium' }).format(d)
}
// cr-0115: reconstruct EVERY managed share's full link (with its `#k=` fragment) by recovering `K_share` via
// `K_mem` — so the curator can re-copy any existing link, not only the just-minted one (ADR 0036). Computed once
// per list load (a symUnwrap per share, not per render); falls back to the fresh link for a pre-cr-0115 share.
const shareLinks = computed(() => {
  const m = new Map<string, string>()
  for (const s of shares.value) {
    const recovered = recoverShareLink(s.shareId, props.memoryKey, s.sealedShareKey)
    const link = recovered ?? (freshLink.value && freshLink.value.includes(`/s/${s.shareId}#`) ? freshLink.value : null)
    if (link) m.set(s.shareId, link)
  }
  return m
})
const copiedLinkId = ref<string | null>(null)
async function copyShareLink(share: ManagedShare) {
  const link = shareLinks.value.get(share.shareId)
  if (!link) return
  try {
    await navigator.clipboard.writeText(link)
    copiedLinkId.value = share.shareId
    setTimeout(() => { if (copiedLinkId.value === share.shareId) copiedLinkId.value = null }, 2000)
  } catch {
    /* clipboard denied — the link stays visible in the row to select manually */
  }
}
</script>

<template>
  <Dialog
    :visible="true"
    :header="t('share.title')"
    modal
    class="share-dialog"
    @update:visible="emit('close')"
  >
    <p class="share-intro">{{ t('share.intro') }}</p>

    <div class="share-role-block">
      <label class="share-role-label">{{ t('share.roleLabel') }}</label>
      <SelectButton v-model="role" :options="roleOptions" option-label="label" option-value="value" :allow-empty="false" />
      <p class="share-role-hint">{{ roleHint }}</p>
    </div>

    <div class="share-note-block">
      <label class="share-role-label" for="share-note-input">{{ t('share.noteLabel') }}</label>
      <textarea
        id="share-note-input"
        v-model="note"
        class="share-note-input"
        rows="2"
        :placeholder="t('share.notePlaceholder')"
      ></textarea>
      <p class="share-role-hint">{{ t('share.noteHint') }}</p>
    </div>

    <div class="share-create-row">
      <Button
        type="button"
        :label="creating ? t('share.creating') : t('share.createLink')"
        icon="pi pi-link"
        :disabled="creating"
        @click="onCreate"
      />
    </div>
    <p v-if="createError" class="share-error">{{ t('share.createFailed') }}</p>

    <div v-if="freshLink" class="share-link-block">
      <label class="share-link-label" for="share-link-input">{{ t('share.linkLabel') }}</label>
      <div class="share-link-row">
        <input id="share-link-input" class="share-link-input" type="text" :value="freshLink" readonly @focus="($event.target as HTMLInputElement).select()" />
        <Button type="button" :label="copied ? t('share.copied') : t('share.copy')" :icon="copied ? 'pi pi-check' : 'pi pi-copy'" severity="secondary" @click="onCopy" />
      </div>
    </div>

    <section class="share-manage">
      <h3>{{ t('share.existing') }}</h3>
      <p v-if="loadError" class="share-error">{{ t('share.loadFailed') }}</p>
      <p v-else-if="!shares.length" class="share-empty">{{ t('share.none') }}</p>
      <ul v-else class="share-list">
        <li v-for="share in shares" :key="share.shareId" class="share-list-item">
          <div class="share-list-top">
            <div class="share-list-meta">
              <span class="share-list-role">{{ roleLabel(share.role) }}</span>
              <span class="share-list-when">{{ t('share.created', { date: fmtDate(share.createdAt) }) }}</span>
              <span v-if="share.expiresAt" class="share-list-expiry">{{ t('share.expires', { date: fmtDate(share.expiresAt) }) }}</span>
              <span v-if="!share.mine" class="share-list-note">{{ t('share.byMember') }}</span>
              <span v-if="!share.enabled" class="share-list-note">{{ t('share.disabled') }}</span>
              <code v-if="shareLinks.get(share.shareId)" class="share-list-link">{{ shareLinks.get(share.shareId) }}</code>
            </div>
            <div class="share-list-actions">
              <Button
                v-if="share.enabled && shareLinks.get(share.shareId)"
                type="button"
                :label="copiedLinkId === share.shareId ? t('share.copied') : t('share.copy')"
                :icon="copiedLinkId === share.shareId ? 'pi pi-check' : 'pi pi-copy'"
                severity="secondary"
                text
                @click="copyShareLink(share)"
              />
              <Button
                v-if="share.mine && share.sealedShareKey && editingId !== share.shareId"
                type="button"
                :label="noteFor(share) ? t('share.noteEdit') : t('share.noteAdd')"
                icon="pi pi-pencil"
                severity="secondary"
                text
                @click="startEditNote(share)"
              />
              <Button
                v-if="!share.enabled"
                type="button"
                :label="reactivatingId === share.shareId ? t('share.reactivating') : t('share.reactivate')"
                icon="pi pi-refresh"
                severity="secondary"
                text
                :disabled="reactivatingId === share.shareId"
                @click="onReactivate(share.shareId)"
              />
              <Button
                v-else-if="share.mine"
                type="button"
                :label="revokingId === share.shareId ? t('share.revoking') : t('share.revoke')"
                icon="pi pi-trash"
                severity="danger"
                text
                :disabled="revokingId === share.shareId"
                @click="onRevoke(share.shareId)"
              />
            </div>
          </div>

          <!-- The current note (curator → viewer), shown when not editing. -->
          <p v-if="editingId !== share.shareId && noteFor(share)" class="share-list-note-text">{{ noteFor(share) }}</p>

          <!-- In-place note editor (cr-0115): recover K_share via K_mem, re-seal, PATCH — the link stays valid. -->
          <div v-if="editingId === share.shareId" class="share-note-edit">
            <textarea v-model="editNote" class="share-note-input" rows="2" :placeholder="t('share.notePlaceholder')"></textarea>
            <div class="share-note-edit-actions">
              <Button type="button" :label="savingNote ? t('share.noteSaving') : t('share.noteSave')" icon="pi pi-check" size="small" :disabled="savingNote" @click="saveNote(share)" />
              <Button type="button" :label="t('share.noteCancel')" severity="secondary" text size="small" :disabled="savingNote" @click="cancelEditNote" />
            </div>
            <p v-if="noteError" class="share-error">{{ t('share.noteSaveFailed') }}</p>
          </div>
        </li>
      </ul>
      <p v-if="revokeError" class="share-error">{{ t('share.revokeFailed') }}</p>
      <p v-if="reactivateError" class="share-error">{{ t('share.reactivateFailed') }}</p>
    </section>
  </Dialog>
</template>

<style scoped>
.share-dialog {
  max-width: 560px;
}
.share-intro {
  margin: 0 0 1rem;
  color: var(--app-muted);
  font-size: 0.9rem;
}
.share-role-block {
  display: grid;
  gap: 0.4rem;
  margin-bottom: 1rem;
}
.share-role-label {
  font-size: 0.85rem;
  font-weight: 600;
}
.share-role-hint {
  margin: 0;
  color: var(--app-muted);
  font-size: 0.82rem;
}
.share-list-role {
  font-size: 0.72rem;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 0.02em;
  color: var(--app-accent);
}
.share-create-row {
  display: flex;
}
.share-error {
  margin: 0.5rem 0 0;
  color: var(--app-danger, #e11d48);
  font-size: 0.85rem;
}
.share-link-block {
  margin-top: 1rem;
  display: grid;
  gap: 0.35rem;
}
.share-link-label {
  color: var(--app-muted);
  font-size: 0.8rem;
}
.share-link-row {
  display: flex;
  gap: 0.5rem;
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
.share-manage {
  margin-top: 1.5rem;
}
.share-manage h3 {
  margin: 0 0 0.5rem;
  font-size: 0.95rem;
}
.share-empty {
  margin: 0;
  color: var(--app-muted);
  font-size: 0.88rem;
}
.share-list-note {
  color: var(--app-muted);
  font-size: 0.8rem;
}
.share-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: 0.5rem;
}
.share-list-item {
  display: grid;
  gap: 0.4rem;
  padding: 0.5rem 0.65rem;
  border: 1px solid var(--app-line);
  border-radius: 10px;
  background: var(--app-surface);
}
.share-list-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.75rem;
}
.share-list-actions {
  display: flex;
  align-items: center;
  gap: 0.25rem;
  flex: 0 0 auto;
}
.share-list-meta {
  display: grid;
  gap: 0.15rem;
  min-width: 0;
}
.share-list-note-text {
  margin: 0;
  padding: 0.4rem 0.55rem;
  border-radius: 8px;
  background: var(--app-surface-muted, rgba(127, 127, 127, 0.08));
  color: var(--app-text);
  font-size: 0.85rem;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.share-note-block {
  display: grid;
  gap: 0.35rem;
  margin-bottom: 1rem;
}
.share-note-input {
  width: 100%;
  box-sizing: border-box;
  padding: 0.45rem 0.6rem;
  border: 1px solid var(--app-line);
  border-radius: 8px;
  background: var(--app-surface);
  color: var(--app-text);
  font: inherit;
  font-size: 0.88rem;
  resize: vertical;
}
.share-note-edit {
  display: grid;
  gap: 0.4rem;
}
.share-note-edit-actions {
  display: flex;
  gap: 0.5rem;
  align-items: center;
}
.share-list-when {
  font-size: 0.85rem;
}
.share-list-expiry {
  color: var(--app-muted);
  font-size: 0.78rem;
}
.share-list-link {
  font-size: 0.75rem;
  color: var(--app-muted);
  overflow-wrap: anywhere;
}
</style>
