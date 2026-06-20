<script setup lang="ts">
// Owner-side public sharing (operator-blind): create a read-only public link for a memory. The scope content
// key is wrapped under a random per-share key that rides the link FRAGMENT (/s/<id>#k=…) and never reaches the
// server; an optional password folds into the unwrap key (Argon2id). List + revoke existing links.
import { ref, watch } from 'vue'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import { createShare, listShares, revokeShare, type ShareSummary } from '../../../features/memory/share/share'
import type { ActiveSpace } from '../../../features/home/home'
import { session } from '../../../app/session'
import { t } from '../../../i18n/i18n'

const props = defineProps<{ active: ActiveSpace; nodeId: string }>()

const shares = ref<ShareSummary[]>([])
const error = ref('')
const busy = ref(false)
const label = ref('')
const password = ref('')
const expiry = ref<'never' | '1d' | '7d' | '30d'>('never')
const createdLink = ref('')
const copied = ref(false)

const EXPIRY_DAYS: Record<string, number> = { '1d': 1, '7d': 7, '30d': 30 }

async function load() {
  error.value = ''
  try {
    shares.value = await listShares(session.apiBase, session.unlocked!, props.active, props.nodeId)
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  }
}

function expiresAtIso(): string | undefined {
  const days = EXPIRY_DAYS[expiry.value]
  return days ? new Date(Date.now() + days * 86_400_000).toISOString() : undefined
}

async function create() {
  busy.value = true
  error.value = ''
  createdLink.value = ''
  copied.value = false
  try {
    const { link } = await createShare(session.apiBase, session.unlocked!, props.active, props.nodeId, {
      role: 'VIEWER',
      label: label.value.trim(),
      password: password.value || undefined,
      expiresAt: expiresAtIso(),
    })
    createdLink.value = link
    label.value = ''
    password.value = ''
    await load()
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    busy.value = false
  }
}

async function copyLink() {
  try {
    await navigator.clipboard.writeText(createdLink.value)
    copied.value = true
  } catch {
    /* clipboard unavailable */
  }
}

async function revoke(share: ShareSummary) {
  busy.value = true
  try {
    await revokeShare(session.apiBase, session.unlocked!, props.active, props.nodeId, share.shareId)
    await load()
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    busy.value = false
  }
}

watch(() => [props.active.scope.scopeId, props.nodeId], load, { immediate: true })
</script>

<template>
  <div class="memory-share">
    <p class="share-hint">{{ t('share.hint') }}</p>
    <p v-if="error" class="share-error">{{ error }}</p>

    <form class="veyq-form share-create" @submit.prevent="create">
      <InputText v-model="label" :placeholder="t('share.labelPlaceholder')" :disabled="busy" />
      <InputText v-model="password" type="password" autocomplete="off" :placeholder="t('share.passwordPlaceholder')" :disabled="busy" />
      <div class="share-expiry">
        <span>{{ t('share.expiry') }}</span>
        <div class="seg">
          <button type="button" :class="{ active: expiry === 'never' }" @click="expiry = 'never'">{{ t('share.expNever') }}</button>
          <button type="button" :class="{ active: expiry === '1d' }" @click="expiry = '1d'">{{ t('share.exp1d') }}</button>
          <button type="button" :class="{ active: expiry === '7d' }" @click="expiry = '7d'">{{ t('share.exp7d') }}</button>
          <button type="button" :class="{ active: expiry === '30d' }" @click="expiry = '30d'">{{ t('share.exp30d') }}</button>
        </div>
      </div>
      <Button type="submit" :label="busy ? t('share.creating') : t('share.create')" icon="pi pi-link" :loading="busy" />
    </form>

    <div v-if="createdLink" class="share-created">
      <code class="share-link">{{ createdLink }}</code>
      <Button type="button" :label="copied ? t('share.copied') : t('share.copy')" :icon="copied ? 'pi pi-check' : 'pi pi-copy'" severity="secondary" outlined @click="copyLink" />
    </div>

    <template v-if="shares.length">
      <h4 class="share-section">{{ t('share.existing') }}</h4>
      <ul class="share-list">
        <li v-for="s in shares" :key="s.shareId" class="share-row">
          <span class="share-meta">
            <strong>{{ s.label || t('share.roleViewer') }}</strong>
            <small>
              <span v-if="s.passwordProtected" class="pi pi-lock" />
              <span v-if="s.expiresAt">{{ t('share.until') }} {{ new Date(s.expiresAt).toLocaleDateString() }}</span>
              <span v-else>{{ t('share.expNever') }}</span>
            </small>
          </span>
          <Button type="button" :label="t('share.revoke')" severity="danger" text :disabled="busy" @click="revoke(s)" />
        </li>
      </ul>
    </template>
  </div>
</template>

<style scoped>
.memory-share { margin: 0.5rem 0 1rem; max-width: 640px; }
.share-hint { opacity: 0.7; font-size: 0.9rem; }
.share-error { color: var(--p-red-400, #f87171); }
.share-create { gap: 0.6rem; }
.share-expiry { display: flex; align-items: center; justify-content: space-between; gap: 1rem; }
.seg { display: inline-flex; border: 1px solid var(--p-content-border-color, rgba(127,127,127,0.3)); border-radius: 8px; overflow: hidden; }
.seg button { background: none; border: none; padding: 0.3rem 0.6rem; cursor: pointer; color: inherit; }
.seg button.active { background: var(--p-primary-color, #6366f1); color: var(--p-primary-contrast-color, #fff); }
.share-created { display: flex; align-items: center; gap: 0.5rem; margin: 0.75rem 0; flex-wrap: wrap; }
.share-link { flex: 1; min-width: 12rem; word-break: break-all; background: var(--p-content-border-color, rgba(127,127,127,0.12)); padding: 0.5rem; border-radius: 6px; font-size: 0.85rem; }
.share-section { margin: 1rem 0 0.5rem; }
.share-list { list-style: none; padding: 0; margin: 0; display: flex; flex-direction: column; gap: 0.4rem; }
.share-row { display: flex; align-items: center; justify-content: space-between; gap: 0.5rem; }
.share-meta { display: flex; flex-direction: column; }
.share-meta small { opacity: 0.6; display: flex; gap: 0.4rem; align-items: center; }
</style>
