<script setup lang="ts">
// The curator's share MANAGEMENT dialog (cr-0116, redesigned toward the legacy shape): a **list-only** view of a
// Memory's VIEWER/CONTRIBUTOR share links + a "New" button that opens the separate create dialog. Uploader (drop-
// box) links are a **separate action** (UploaderShareDialog) and are filtered out here. Each row shows the share's
// private name (or the role), badges (note present / disabled / by another member), and per-row actions: **copy
// link** (copy-only — the URL is reconstructed via the K_mem-recoverable K_share, cr-0115), edit name+note, and
// revoke / re-activate. Operator-blind throughout: names/notes are sealed under K_share and decrypted on device.
import { ref, computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import Dialog from 'primevue/dialog'
import Button from 'primevue/button'
import ShareCreateDialog from './ShareCreateDialog.vue'
import {
  listSharesFor,
  revokeShare,
  reactivateShare,
  recoverShareLink,
  readShareName,
  readShareNoteMap,
  hasLocaleText,
  type ManagedShare,
} from '../share'
import { ShareRole } from '../../../api/generated/sharing'

const props = defineProps<{ nodeId: string; memoryKey: Uint8Array; memoryName: string }>()
const emit = defineEmits<{ close: [] }>()

const { t, locale } = useI18n()

const shares = ref<ManagedShare[]>([])
const loadError = ref(false)
const revokingId = ref<string | null>(null)
const reactivatingId = ref<string | null>(null)
const actionError = ref(false)
const copiedLinkId = ref<string | null>(null)

// The create/edit dialog: `createOpen` for a new share, `editShare` for editing an existing one's name + note.
const createOpen = ref(false)
const editShare = ref<ManagedShare | null>(null)

// VIEWER/CONTRIBUTOR only — uploader drop-boxes are managed in their own dialog (a separate memory action).
const visibleShares = computed(() => shares.value.filter((s) => s.role !== ShareRole.Uploader))

function roleLabel(r: ShareRole | string): string {
  if (r === ShareRole.Contributor) return t('share.roleContributor')
  return t('share.roleViewer')
}

// Decrypt each share's name + note-presence once per load (a symUnwrap per share, not per render).
const decoded = computed(() => {
  const m = new Map<string, { name: string; hasNote: boolean }>()
  for (const s of shares.value) {
    m.set(s.shareId, {
      name: readShareName(props.memoryKey, s.sealedShareKey, s.sealedShareName),
      hasNote: hasLocaleText(readShareNoteMap(props.memoryKey, s.sealedShareKey, s.sealedNote)),
    })
  }
  return m
})
const shareLinks = computed(() => {
  const m = new Map<string, string>()
  for (const s of shares.value) {
    const link = recoverShareLink(s.shareId, props.memoryKey, s.sealedShareKey)
    if (link) m.set(s.shareId, link)
  }
  return m
})
function titleFor(share: ManagedShare): string {
  return decoded.value.get(share.shareId)?.name || roleLabel(share.role)
}

async function loadShares() {
  loadError.value = false
  try {
    shares.value = await listSharesFor(props.nodeId)
  } catch {
    loadError.value = true
  }
}
onMounted(loadShares)

function openCreate() {
  editShare.value = null
  createOpen.value = true
}
function openEdit(share: ManagedShare) {
  editShare.value = share
  createOpen.value = true
}
async function onSaved() {
  await loadShares()
}
function onDialogClose() {
  createOpen.value = false
  editShare.value = null
}

async function copyShareLink(share: ManagedShare) {
  const link = shareLinks.value.get(share.shareId)
  if (!link) return
  try {
    await navigator.clipboard.writeText(link)
    copiedLinkId.value = share.shareId
    setTimeout(() => { if (copiedLinkId.value === share.shareId) copiedLinkId.value = null }, 2000)
  } catch {
    /* clipboard denied */
  }
}

async function onRevoke(shareId: string) {
  revokingId.value = shareId
  actionError.value = false
  try {
    await revokeShare(shareId)
    await loadShares()
  } catch {
    actionError.value = true
  } finally {
    revokingId.value = null
  }
}
async function onReactivate(shareId: string) {
  reactivatingId.value = shareId
  actionError.value = false
  try {
    await reactivateShare(shareId)
    await loadShares()
  } catch {
    actionError.value = true
  } finally {
    reactivatingId.value = null
  }
}

function fmtDate(d: Date): string {
  return new Intl.DateTimeFormat(locale.value === 'de' ? 'de-DE' : 'en-US', { dateStyle: 'medium' }).format(d)
}
</script>

<template>
  <Dialog :visible="true" :header="t('share.title')" modal class="share-dialog" @update:visible="emit('close')">
    <div class="share-manage-head">
      <p class="share-intro">{{ t('share.intro') }}</p>
      <Button :label="t('share.createNew')" icon="pi pi-plus" size="small" @click="openCreate" />
    </div>

    <p v-if="loadError" class="share-error">{{ t('share.loadFailed') }}</p>
    <p v-else-if="!visibleShares.length" class="share-empty">{{ t('share.none') }}</p>

    <ul v-else class="share-list">
      <li v-for="share in visibleShares" :key="share.shareId" class="share-list-item">
        <div class="share-list-meta">
          <strong class="share-list-title">{{ titleFor(share) }}</strong>
          <span class="share-list-when">{{ t('share.created', { date: fmtDate(share.createdAt) }) }}</span>
          <div class="share-list-badges">
            <span class="share-list-role">{{ roleLabel(share.role) }}</span>
            <span v-if="decoded.get(share.shareId)?.hasNote" class="share-badge">{{ t('share.hasNote') }}</span>
            <span v-if="!share.mine" class="share-badge muted">{{ t('share.byMember') }}</span>
            <span v-if="!share.enabled" class="share-badge muted">{{ t('share.disabled') }}</span>
          </div>
        </div>
        <div class="share-list-actions">
          <Button
            v-if="share.enabled && shareLinks.get(share.shareId)"
            type="button"
            :icon="copiedLinkId === share.shareId ? 'pi pi-check' : 'pi pi-copy'"
            :aria-label="t('share.copy')"
            :title="copiedLinkId === share.shareId ? t('share.copied') : t('share.copy')"
            severity="secondary"
            text
            @click="copyShareLink(share)"
          />
          <Button
            v-if="share.mine && share.sealedShareKey"
            type="button"
            icon="pi pi-pencil"
            :aria-label="t('share.edit')"
            :title="t('share.edit')"
            severity="secondary"
            text
            @click="openEdit(share)"
          />
          <Button
            v-if="!share.enabled"
            type="button"
            icon="pi pi-refresh"
            :aria-label="t('share.reactivate')"
            :title="t('share.reactivate')"
            severity="secondary"
            text
            :disabled="reactivatingId === share.shareId"
            @click="onReactivate(share.shareId)"
          />
          <Button
            v-else-if="share.mine"
            type="button"
            icon="pi pi-trash"
            :aria-label="t('share.revoke')"
            :title="t('share.revoke')"
            severity="danger"
            text
            :disabled="revokingId === share.shareId"
            @click="onRevoke(share.shareId)"
          />
        </div>
      </li>
    </ul>
    <p v-if="actionError" class="share-error">{{ t('share.actionFailed') }}</p>

    <ShareCreateDialog
      v-if="createOpen"
      :node-id="nodeId"
      :memory-key="memoryKey"
      :memory-name="memoryName"
      :edit-share="editShare"
      @saved="onSaved"
      @close="onDialogClose"
    />
  </Dialog>
</template>

<style scoped>
.share-manage-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 1rem;
  margin-bottom: 1rem;
}
.share-intro {
  margin: 0;
  color: var(--app-muted);
  font-size: 0.9rem;
}
.share-error {
  margin: 0.5rem 0 0;
  color: var(--app-danger, #e11d48);
  font-size: 0.85rem;
}
.share-empty {
  margin: 0;
  color: var(--app-muted);
  font-size: 0.88rem;
}
.share-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: 0.5rem;
}
.share-list-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.75rem;
  padding: 0.55rem 0.7rem;
  border: 1px solid var(--app-line);
  border-radius: 10px;
  background: var(--app-surface);
}
.share-list-meta {
  display: grid;
  gap: 0.2rem;
  min-width: 0;
}
.share-list-title {
  font-size: 0.95rem;
  overflow-wrap: anywhere;
}
.share-list-when {
  color: var(--app-muted);
  font-size: 0.78rem;
}
.share-list-badges {
  display: flex;
  flex-wrap: wrap;
  gap: 0.35rem;
  align-items: center;
}
.share-list-role {
  font-size: 0.68rem;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 0.02em;
  color: var(--app-accent);
}
.share-badge {
  font-size: 0.72rem;
  padding: 0.05rem 0.4rem;
  border-radius: 999px;
  background: var(--app-surface-muted, rgba(127, 127, 127, 0.12));
  color: var(--app-text);
}
.share-badge.muted {
  color: var(--app-muted);
}
.share-list-actions {
  display: flex;
  align-items: center;
  gap: 0.15rem;
  flex: 0 0 auto;
}
</style>
