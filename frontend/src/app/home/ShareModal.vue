<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { session } from '../session'
import { t } from '../../i18n/i18n'
import { createShare, listShares, revokeShare, type ShareRole, type ShareSummary } from '../../features/memory/share/share'
import type { ActiveSpace, MemoryNode } from '../../features/home/home'

const props = defineProps<{ active: ActiveSpace; memory: MemoryNode; name: string }>()
const emit = defineEmits<{ close: [] }>()

const shares = ref<ShareSummary[]>([])
const role = ref<ShareRole>('VIEWER')
const password = ref('')
const expiry = ref<'never' | '1d' | '7d' | '30d'>('never')
const label = ref('')
const link = ref('')
const busy = ref(false)
const copied = ref(false)
const error = ref('')

const EXPIRY_DAYS: Record<string, number> = { '1d': 1, '7d': 7, '30d': 30 }
function expiresAtIso(): string | undefined {
  const days = EXPIRY_DAYS[expiry.value]
  return days ? new Date(Date.now() + days * 86400_000).toISOString() : undefined
}

onMounted(async () => {
  try {
    shares.value = await listShares(session.apiBase, session.unlocked!, props.active, props.memory.id)
  } catch {
    /* ignore */
  }
})

async function create() {
  busy.value = true
  error.value = ''
  try {
    const res = await createShare(session.apiBase, session.unlocked!, props.active, props.memory.id, {
      role: role.value,
      password: password.value.trim() || undefined,
      expiresAt: expiresAtIso(),
      label: label.value.trim() || undefined,
    })
    link.value = res.link
    shares.value = await listShares(session.apiBase, session.unlocked!, props.active, props.memory.id)
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    busy.value = false
  }
}

async function copy() {
  await navigator.clipboard.writeText(link.value)
  copied.value = true
  setTimeout(() => (copied.value = false), 1500)
}

async function revoke(shareId: string) {
  await revokeShare(session.apiBase, session.unlocked!, props.active, props.memory.id, shareId)
  shares.value = shares.value.filter((s) => s.shareId !== shareId)
}
</script>

<template>
  <div class="settings-backdrop" @click.self="emit('close')">
    <div class="settings-panel">
      <div class="settings-head">
        <h2 class="settings-title">{{ t('share.share') }} · {{ name }}</h2>
        <button class="btn btn-ghost" type="button" @click="emit('close')">{{ t('common.close') }}</button>
      </div>

      <p class="invite-hint">{{ t('share.hint') }}</p>
      <template v-if="!link">
        <label class="invite-role">
          {{ t('invite.role') }}
          <div class="seg">
            <button type="button" :class="{ active: role === 'VIEWER' }" @click="role = 'VIEWER'">{{ t('share.roleViewer') }}</button>
            <button type="button" :class="{ active: role === 'CONTRIBUTOR' }" @click="role = 'CONTRIBUTOR'">{{ t('share.roleContributor') }}</button>
            <button type="button" :class="{ active: role === 'UPLOADER' }" @click="role = 'UPLOADER'">{{ t('share.roleUploader') }}</button>
          </div>
        </label>
        <label class="invite-role">
          {{ t('share.expiry') }}
          <div class="seg">
            <button type="button" :class="{ active: expiry === 'never' }" @click="expiry = 'never'">{{ t('share.expNever') }}</button>
            <button type="button" :class="{ active: expiry === '1d' }" @click="expiry = '1d'">{{ t('share.exp1d') }}</button>
            <button type="button" :class="{ active: expiry === '7d' }" @click="expiry = '7d'">{{ t('share.exp7d') }}</button>
            <button type="button" :class="{ active: expiry === '30d' }" @click="expiry = '30d'">{{ t('share.exp30d') }}</button>
          </div>
        </label>
        <input class="share-field" v-model="label" type="text" :placeholder="t('share.labelPlaceholder')" />
        <input class="share-field" v-model="password" type="password" autocomplete="new-password" :placeholder="t('share.passwordPlaceholder')" />
      </template>
      <div v-if="error" class="auth-msg auth-msg-error">{{ error }}</div>

      <template v-if="link">
        <div class="invite-link-row">
          <input class="invite-link" :value="link" readonly @focus="($event.target as HTMLInputElement).select()" />
          <button class="btn btn-primary" type="button" @click="copy">{{ copied ? t('share.copied') : t('share.copy') }}</button>
        </div>
      </template>
      <button v-else class="btn btn-primary invite-gen" type="button" :disabled="busy" @click="create">{{ busy ? t('share.creating') : t('share.create') }}</button>

      <template v-if="shares.length">
        <div class="settings-section">{{ t('share.existing') }}</div>
        <ul class="track-list">
          <li v-for="s in shares" :key="s.shareId" class="track-row">
            <span class="track-name">{{ s.passwordProtected ? '🔒' : '🔗' }} {{ s.label || s.shareId.slice(0, 8) + '…' }}<small v-if="s.expiresAt" class="share-exp"> · {{ t('share.until') }} {{ new Date(s.expiresAt).toLocaleDateString() }}</small></span>
            <button class="btn btn-ghost track-del" type="button" @click="revoke(s.shareId)">{{ t('share.revoke') }}</button>
          </li>
        </ul>
      </template>
    </div>
  </div>
</template>
