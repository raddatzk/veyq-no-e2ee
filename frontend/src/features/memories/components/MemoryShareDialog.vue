<script setup lang="ts">
// Owner-side public sharing for a memory (operator-blind). createShare wraps the scope content key under a
// random per-share key; that key rides the returned link FRAGMENT and never reaches the server — so the link
// is only available at creation time (existing shares show metadata + revoke only). Guests open it via the
// public /s/<id> route.
import { ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import Password from 'primevue/password'
import { createShare, listShares, revokeShare, type ShareRole, type ShareSummary } from '../../memory/share/share'
import { requireSession, session } from '../../../app/session'
import type { ActiveSpace } from '../../../features/home/home'

const props = defineProps<{ active: ActiveSpace; nodeId: string }>()
const visible = defineModel<boolean>('visible', { required: true })
const { t } = useI18n()

const shares = ref<ShareSummary[]>([])
const role = ref<ShareRole>('VIEWER')
const password = ref('')
const expiresAt = ref('')
const label = ref('')
const createdLink = ref('')
const busy = ref(false)
const error = ref('')
const message = ref('')

watch(visible, (open) => { if (open) void load() })

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
      role: role.value,
      password: password.value || undefined,
      expiresAt: expiresAt.value || undefined,
      label: label.value || undefined,
      origin: window.location.origin,
    })
    await navigator.clipboard?.writeText(res.link).catch(() => {})
    message.value = t('share.linkCopied')
    password.value = ''
    label.value = ''
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
  <Dialog v-model:visible="visible" :header="t('share.title')" modal class="tenant-dialog">
    <form class="veyq-form" @submit.prevent="create">
      <label>
        {{ t('share.role') }}
        <select v-model="role" class="share-select">
          <option value="VIEWER">{{ t('share.roles.VIEWER') }}</option>
          <option value="CONTRIBUTOR">{{ t('share.roles.CONTRIBUTOR') }}</option>
          <option value="UPLOADER">{{ t('share.roles.UPLOADER') }}</option>
        </select>
      </label>
      <label>
        {{ t('share.labelPlaceholder') }}
        <InputText v-model="label" autocomplete="off" />
      </label>
      <label>
        {{ t('share.passwordProtection') }}
        <Password v-model="password" :input-props="{ autocomplete: 'off' }" :feedback="false" toggle-mask fluid />
      </label>
      <label>
        {{ t('share.expiresLabel') }}
        <InputText v-model="expiresAt" type="date" />
      </label>
      <Button type="submit" :label="t('share.create')" icon="pi pi-link" :loading="busy" />
    </form>

    <div v-if="createdLink" class="share-created">
      <code class="share-link">{{ createdLink }}</code>
    </div>

    <section class="share-list">
      <p v-if="shares.length === 0" class="share-empty">{{ t('share.empty') }}</p>
      <div v-for="s in shares" :key="s.shareId" class="share-row">
        <div>
          <strong>{{ s.label || t('share.roles.' + s.role) }}</strong>
          <small>{{ t('share.roles.' + s.role) }} · {{ s.passwordProtected ? t('share.hasPassword') : t('share.noPassword') }}<template v-if="s.expiresAt"> · {{ new Date(s.expiresAt).toLocaleDateString() }}</template></small>
        </div>
        <Button type="button" icon="pi pi-trash" severity="danger" text :aria-label="t('share.revoke')" @click="revoke(s)" />
      </div>
    </section>

    <Message v-if="message" severity="success" :closable="false">{{ message }}</Message>
    <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>
  </Dialog>
</template>

<style scoped>
.share-select { padding: 0.45rem 0.6rem; border-radius: 8px; border: 1px solid var(--p-content-border-color, rgba(127,127,127,0.25)); background: var(--p-content-background, transparent); color: inherit; }
.share-created { margin: 0.75rem 0; }
.share-link { display: block; padding: 0.5rem; border-radius: 8px; background: var(--p-content-border-color, rgba(127,127,127,0.12)); word-break: break-all; font-size: 0.8rem; }
.share-list { margin-top: 1rem; }
.share-empty { opacity: 0.55; font-size: 0.9rem; }
.share-row { display: flex; align-items: center; gap: 0.5rem; padding: 0.5rem 0; border-bottom: 1px solid var(--p-content-border-color, rgba(127,127,127,0.15)); }
.share-row div { flex: 1; }
.share-row strong { display: block; }
.share-row small { opacity: 0.6; }
</style>
