<script setup lang="ts">
// The uploader (drop-box) share dialog (cr-0116) — a SEPARATE memory action from the normal share dialog, matching
// the legacy shape. An UPLOADER link lets anyone add photos but not see the Memory or others' contributions
// (ADR 0031). Reduced create form (just an optional name, hardcoded role UPLOADER — no role switch, no note) + a
// list of this Memory's uploader links with copy (copy-only) / revoke. Operator-blind: the name is sealed under
// K_share; the link is reconstructed via the K_mem-recoverable K_share (cr-0115).
import { ref, computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import Dialog from 'primevue/dialog'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import {
  createShare,
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
const name = ref('')
const creating = ref(false)
const createError = ref(false)
const freshLink = ref('')
const copiedId = ref<string | null>(null)
const revokingId = ref<string | null>(null)
const actionError = ref(false)

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

async function onCreate() {
  creating.value = true
  createError.value = false
  freshLink.value = ''
  try {
    const minted = await createShare(props.nodeId, props.memoryKey, props.memoryName, ShareRole.Uploader, null, {}, name.value)
    freshLink.value = minted.link
    name.value = ''
    await loadShares()
  } catch {
    createError.value = true
  } finally {
    creating.value = false
  }
}

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
    <p class="share-intro">{{ t('share.uploaderDialogCopy') }}</p>

    <div class="share-field">
      <label for="uploader-name">{{ t('share.name') }}</label>
      <InputText id="uploader-name" v-model="name" :placeholder="t('share.nameOptional')" />
    </div>
    <div class="share-create-row">
      <Button
        :label="creating ? t('share.creating') : t('share.uploaderCreate')"
        icon="pi pi-user-plus"
        :disabled="creating"
        @click="onCreate"
      />
    </div>
    <p v-if="createError" class="share-error">{{ t('share.createFailed') }}</p>

    <div v-if="freshLink" class="share-link-row">
      <input class="share-link-input" type="text" :value="freshLink" readonly @focus="($event.target as HTMLInputElement).select()" />
      <Button
        :label="copiedId === 'fresh' ? t('share.copied') : t('share.copy')"
        :icon="copiedId === 'fresh' ? 'pi pi-check' : 'pi pi-copy'"
        severity="secondary"
        @click="copyLink('fresh', freshLink)"
      />
    </div>

    <section class="share-manage">
      <h3>{{ t('share.uploaderExisting') }}</h3>
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
    </section>
  </Dialog>
</template>

<style scoped>
.share-intro {
  margin: 0 0 1rem;
  color: var(--app-muted);
  font-size: 0.9rem;
}
.share-field {
  display: grid;
  gap: 0.4rem;
  margin-bottom: 0.75rem;
}
.share-field > label {
  font-size: 0.85rem;
  font-weight: 600;
}
.share-create-row {
  display: flex;
}
.share-error {
  margin: 0.5rem 0 0;
  color: var(--app-danger, #e11d48);
  font-size: 0.85rem;
}
.share-link-row {
  display: flex;
  gap: 0.5rem;
  margin-top: 1rem;
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
