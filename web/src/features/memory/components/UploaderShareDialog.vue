<script setup lang="ts">
// The uploader (drop-box) share dialog (cr-0116) — a SEPARATE memory action from the normal share dialog. An
// UPLOADER link lets anyone add photos but not see the Memory or others' contributions (ADR 0031). Like the normal
// shares, it is **list-only + a "New" button** that opens the SAME create dialog (ShareCreateDialog) with the role
// fixed to UPLOADER — so an uploader link is created with a name + a per-locale note just like the others
// (cr-0116 follow-up). Rows show copy (copy-only, the link reconstructed via the K_mem-recoverable K_share) +
// revoke. Operator-blind: name/note sealed under K_share.
import { ref, computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import Dialog from 'primevue/dialog'
import Button from 'primevue/button'
import ShareCreateDialog from './ShareCreateDialog.vue'
import {
  listSharesFor,
  revokeShare,
  recoverShareLink,
  readShareName,
  type ManagedShare,
} from '../share'
import { ShareRole } from '../../../api/generated/sharing'

const props = defineProps<{ nodeId: string; memoryKey: Uint8Array; memoryName: string }>()
const emit = defineEmits<{ close: [] }>()
const { t, locale } = useI18n()

const shares = ref<ManagedShare[]>([])
const loadError = ref(false)
const copiedId = ref<string | null>(null)
const revokingId = ref<string | null>(null)
const actionError = ref(false)
const createOpen = ref(false)

const uploaderShares = computed(() => shares.value.filter((s) => s.role === ShareRole.Uploader))
const decodedNames = computed(() => {
  const m = new Map<string, string>()
  for (const s of uploaderShares.value) m.set(s.shareId, readShareName(props.memoryKey, s.sealedShareKey, s.sealedShareName))
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
  return decodedNames.value.get(share.shareId) || t('share.uploaderDefaultName')
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

async function copyLink(id: string, link: string) {
  try {
    await navigator.clipboard.writeText(link)
    copiedId.value = id
    setTimeout(() => { if (copiedId.value === id) copiedId.value = null }, 2000)
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

function fmtDate(d: Date): string {
  return new Intl.DateTimeFormat(locale.value === 'de' ? 'de-DE' : 'en-US', { dateStyle: 'medium' }).format(d)
}
</script>

<template>
  <Dialog :visible="true" :header="t('share.uploaderTitle')" modal class="share-dialog" @update:visible="emit('close')">
    <div class="share-manage-head">
      <p class="share-intro">{{ t('share.uploaderDialogCopy') }}</p>
      <Button :label="t('share.uploaderCreate')" icon="pi pi-user-plus" size="small" @click="createOpen = true" />
    </div>

    <p v-if="loadError" class="share-error">{{ t('share.loadFailed') }}</p>
    <p v-else-if="!uploaderShares.length" class="share-empty">{{ t('share.uploaderNone') }}</p>
    <ul v-else class="share-list">
      <li v-for="share in uploaderShares" :key="share.shareId" class="share-list-item">
        <div class="share-list-meta">
          <strong class="share-list-title">{{ titleFor(share) }}</strong>
          <span class="share-list-when">{{ t('share.created', { date: fmtDate(share.createdAt) }) }}</span>
          <span v-if="!share.enabled" class="share-badge muted">{{ t('share.disabled') }}</span>
        </div>
        <div class="share-list-actions">
          <Button
            v-if="share.enabled && shareLinks.get(share.shareId)"
            type="button"
            :icon="copiedId === share.shareId ? 'pi pi-check' : 'pi pi-copy'"
            :aria-label="t('share.copy')"
            :title="copiedId === share.shareId ? t('share.copied') : t('share.copy')"
            severity="secondary"
            text
            @click="copyLink(share.shareId, shareLinks.get(share.shareId)!)"
          />
          <Button
            v-if="share.mine && share.enabled"
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
      :fixed-role="ShareRole.Uploader"
      @saved="loadShares"
      @close="createOpen = false"
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
.share-badge.muted {
  font-size: 0.72rem;
  color: var(--app-muted);
}
.share-list-actions {
  display: flex;
  align-items: center;
  gap: 0.15rem;
  flex: 0 0 auto;
}
</style>
