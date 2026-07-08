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
import { createShare, listSharesFor, revokeShare, type ManagedShare } from '../share'
import { ShareRole } from '../../../api/generated/sharing'

const props = defineProps<{
  nodeId: string
  memoryKey: Uint8Array
  memoryName: string
}>()
const emit = defineEmits<{ close: [] }>()

const { t, locale } = useI18n()

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

// The share role to mint: VIEWER (read-only, cr-0100), CONTRIBUTOR (view + add photos, cr-0101), or
// UPLOADER (write-only drop-box — add photos but can't see the Memory or others', cr-0102, ADR 0031).
const role = ref<ShareRole>(ShareRole.Viewer)
const roleOptions = computed(() => [
  { label: t('share.roleViewer'), value: ShareRole.Viewer },
  { label: t('share.roleContributor'), value: ShareRole.Contributor },
  { label: t('share.roleUploader'), value: ShareRole.Uploader },
])
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
    const minted = await createShare(props.nodeId, props.memoryKey, props.memoryName, role.value)
    freshLink.value = minted.link
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

function fmtDate(d: Date): string {
  return new Intl.DateTimeFormat(locale.value === 'de' ? 'de-DE' : 'en-US', { dateStyle: 'medium' }).format(d)
}
// A share whose link the curator can still see this session (only the freshly-minted one carries its fragment key).
function linkFor(share: ManagedShare): string | null {
  return freshLink.value && freshLink.value.includes(`/s/${share.shareId}#`) ? freshLink.value : null
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
          <div class="share-list-meta">
            <span class="share-list-role">{{ roleLabel(share.role) }}</span>
            <span class="share-list-when">{{ t('share.created', { date: fmtDate(share.createdAt) }) }}</span>
            <span v-if="share.expiresAt" class="share-list-expiry">{{ t('share.expires', { date: fmtDate(share.expiresAt) }) }}</span>
            <code v-if="linkFor(share)" class="share-list-link">{{ linkFor(share) }}</code>
          </div>
          <Button
            type="button"
            :label="revokingId === share.shareId ? t('share.revoking') : t('share.revoke')"
            icon="pi pi-trash"
            severity="danger"
            text
            :disabled="revokingId === share.shareId"
            @click="onRevoke(share.shareId)"
          />
        </li>
      </ul>
      <p v-if="revokeError" class="share-error">{{ t('share.revokeFailed') }}</p>
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
  padding: 0.5rem 0.65rem;
  border: 1px solid var(--app-line);
  border-radius: 10px;
  background: var(--app-surface);
}
.share-list-meta {
  display: grid;
  gap: 0.15rem;
  min-width: 0;
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
