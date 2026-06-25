<script setup lang="ts">
// Uploader-link sharing: a dedicated, UPLOADER-only variant of the public share flow (operator-blind). Unlike
// the general share dialog it offers no role choice — every link is upload-only (no gallery / no other
// contributions for the guest) — and it lists/revokes only the UPLOADER shares of this memory. createShare
// wraps the scope content key under a per-share key that rides the link fragment and never reaches the server.
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import Password from 'primevue/password'
import { createShare, listShares, revokeShare, type ShareSummary } from '../../memory/share/share'
import { requireSession, session } from '../../../app/session'
import type { ActiveSpace } from '../../../features/home/home'

const props = defineProps<{ active: ActiveSpace; nodeId: string }>()
const visible = defineModel<boolean>('visible', { required: true })
const { t } = useI18n()

const shares = ref<ShareSummary[]>([])
const label = ref('')
const password = ref('')
const expiresAt = ref('')
const createdLink = ref('')
const busy = ref(false)
const error = ref('')
const message = ref('')

const uploaderShares = computed(() => shares.value.filter((s) => s.role === 'UPLOADER'))

watch(visible, (open) => { if (open) { reset(); void load() } })

function reset() {
  label.value = ''
  password.value = ''
  expiresAt.value = ''
  createdLink.value = ''
  error.value = ''
  message.value = ''
}

async function load() {
  error.value = ''
  try {
    shares.value = await listShares(session.apiBase, requireSession(), props.active, props.nodeId)
  } catch {
    error.value = t('share.loadFailed')
  }
}

async function create() {
  busy.value = true
  error.value = ''
  message.value = ''
  createdLink.value = ''
  try {
    const res = await createShare(session.apiBase, requireSession(), props.active, props.nodeId, {
      role: 'UPLOADER',
      password: password.value || undefined,
      expiresAt: expiresAt.value || undefined,
      label: label.value || t('share.uploaderDefaultName'),
      origin: window.location.origin,
    })
    await navigator.clipboard?.writeText(res.link).catch(() => {})
    message.value = t('share.linkCopied')
    label.value = ''
    password.value = ''
    expiresAt.value = ''
    await load()
    createdLink.value = res.link // keep the freshly created link visible (the key is client-only)
  } catch {
    error.value = t('share.createFailed')
  } finally {
    busy.value = false
  }
}

async function revoke(share: ShareSummary) {
  error.value = ''
  try {
    await revokeShare(session.apiBase, requireSession(), props.active, props.nodeId, share.shareId)
    shares.value = shares.value.filter((s) => s.shareId !== share.shareId)
    message.value = t('share.revoked')
  } catch {
    error.value = t('share.revokeFailed')
  }
}
</script>

<template>
  <Dialog v-model:visible="visible" :header="t('share.uploaderTitle')" modal class="tenant-dialog">
    <p class="uploader-copy">{{ t('share.uploaderDialogCopy') }}</p>
    <Message severity="info" :closable="false">{{ t('share.uploaderOnlyNotice') }}</Message>

    <form class="veyq-form" @submit.prevent="create">
      <label>
        {{ t('share.labelPlaceholder') }}
        <InputText v-model="label" autocomplete="off" :placeholder="t('share.uploaderDefaultName')" />
      </label>
      <label>
        {{ t('share.passwordProtection') }}
        <Password v-model="password" :input-props="{ autocomplete: 'off' }" :feedback="false" toggle-mask fluid />
      </label>
      <label>
        {{ t('share.expiresLabel') }}
        <InputText v-model="expiresAt" type="date" />
      </label>
      <Button type="submit" :label="t('share.uploaderCreate')" icon="pi pi-user-plus" :loading="busy" />
    </form>

    <div v-if="createdLink" class="share-created">
      <code class="share-link">{{ createdLink }}</code>
    </div>

    <section class="share-list">
      <p v-if="uploaderShares.length === 0" class="share-empty">{{ t('share.empty') }}</p>
      <div v-for="s in uploaderShares" :key="s.shareId" class="share-row">
        <div>
          <strong>{{ s.label || t('share.uploaderDefaultName') }}</strong>
          <small>{{ s.passwordProtected ? t('share.hasPassword') : t('share.noPassword') }}<template v-if="s.expiresAt"> · {{ new Date(s.expiresAt).toLocaleDateString() }}</template></small>
        </div>
        <Button type="button" icon="pi pi-trash" severity="danger" text :aria-label="t('share.revoke')" @click="revoke(s)" />
      </div>
    </section>

    <Message v-if="message" severity="success" :closable="false">{{ message }}</Message>
    <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>
  </Dialog>
</template>

<style scoped>
.uploader-copy { opacity: 0.75; font-size: 0.9rem; margin: 0 0 0.5rem; }
.share-created { margin: 0.75rem 0; }
.share-link { display: block; padding: 0.5rem; border-radius: 8px; background: var(--p-content-border-color, rgba(127,127,127,0.12)); word-break: break-all; font-size: 0.8rem; }
.share-list { margin-top: 1rem; }
.share-empty { opacity: 0.55; font-size: 0.9rem; }
.share-row { display: flex; align-items: center; gap: 0.5rem; padding: 0.5rem 0; border-bottom: 1px solid var(--p-content-border-color, rgba(127,127,127,0.15)); }
.share-row div { flex: 1; }
.share-row strong { display: block; }
.share-row small { opacity: 0.6; }
</style>
