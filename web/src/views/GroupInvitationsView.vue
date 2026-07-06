<script setup lang="ts">
// uc.spaces.consent-group-add (web, cr-0096, ADR 0027) — the invitee's "Group invitations" inbox. Lists the
// caller's pending consent proposals in the active tenant; each carries the group's **name-key `K_name` wrapped to
// the caller's own identity** + the scope's **canonical `encryptedName`** it opens (both combined on-device here to
// the group-consistent name) + who added them — so a modified owner cannot show a divergent name. The invitee
// decides: Accept (→ grant-delivery wraps K_SC the usual way) or Decline (a silent drop — nothing was ever wrapped).
// Operator-blind: the server only ever held an opaque wrapped name-key + the already-stored name ciphertext + the
// membership row, never K_SC, the name-key it can use, or the name.
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import Button from 'primevue/button'
import Message from 'primevue/message'
import {
  listMyGroupInvitations,
  acceptGroupInvitation,
  declineGroupInvitation,
  memberError,
} from '../features/spaces/members'
import { openInviteeName } from '../features/spaces/inviteName'

/** A pending invitation with its group name decrypted on-device (`name` null when the ciphertext won't open). */
type InvitationRow = { scopeId: string; addedBy: string; name: string | null }

const { t } = useI18n()
const router = useRouter()

const invitations = ref<InvitationRow[]>([])
const loading = ref(true)
const loadError = ref(false)
const actionError = ref('')
const busy = ref<string | null>(null) // scopeId currently being accepted/declined

onMounted(load)

async function load() {
  loading.value = true
  loadError.value = false
  actionError.value = ''
  try {
    const rows = await listMyGroupInvitations()
    invitations.value = rows.map((r) => ({ scopeId: r.scopeId, addedBy: r.addedBy, name: openInviteeName(r.sealedNameKey, r.encryptedName) }))
  } catch (e) {
    const { status } = await memberError(e)
    if (status === 401) return void router.push({ name: 'login' })
    loadError.value = true
  } finally {
    loading.value = false
  }
}

async function accept(inv: InvitationRow) {
  actionError.value = ''
  busy.value = inv.scopeId
  try {
    await acceptGroupInvitation(inv.scopeId)
    invitations.value = invitations.value.filter((i) => i.scopeId !== inv.scopeId)
  } catch (e) {
    const { status } = await memberError(e)
    if (status === 401) return void router.push({ name: 'login' })
    actionError.value = t('invitations.actionFailed')
  } finally {
    busy.value = null
  }
}

async function decline(inv: InvitationRow) {
  actionError.value = ''
  busy.value = inv.scopeId
  try {
    await declineGroupInvitation(inv.scopeId)
    invitations.value = invitations.value.filter((i) => i.scopeId !== inv.scopeId)
  } catch (e) {
    const { status } = await memberError(e)
    if (status === 401) return void router.push({ name: 'login' })
    actionError.value = t('invitations.actionFailed')
  } finally {
    busy.value = null
  }
}
</script>

<template>
  <section class="veyq-surface settings-panel invitations-view">
    <section class="settings-form">
      <div class="settings-section-header">
        <div>
          <h2>{{ t('invitations.title') }}</h2>
          <p>{{ t('invitations.sub') }}</p>
        </div>
      </div>

      <p v-if="loading" class="settings-muted">{{ t('invitations.loading') }}</p>
      <Message v-else-if="loadError" severity="error" :closable="false">{{ t('invitations.loadFailed') }}</Message>
      <p v-else-if="invitations.length === 0" class="settings-muted">{{ t('invitations.empty') }}</p>

      <template v-else>
        <Message v-if="actionError" severity="error" :closable="false">{{ actionError }}</Message>
        <div class="veyq-list management-list">
          <div class="veyq-list management-list-items">
            <div v-for="inv in invitations" :key="inv.scopeId" class="veyq-list-row member-row">
              <div class="veyq-row-main">
                <strong class="veyq-row-title">{{ inv.name || t('invitations.unnamedGroup') }}</strong>
                <span class="veyq-row-subtitle">{{ t('invitations.addedBy', { user: inv.addedBy }) }}</span>
              </div>
              <div class="management-actions">
                <Button
                  :label="t('invitations.accept')"
                  icon="pi pi-check"
                  size="small"
                  :loading="busy === inv.scopeId"
                  @click="accept(inv)"
                />
                <Button
                  :label="t('invitations.decline')"
                  icon="pi pi-times"
                  severity="secondary"
                  outlined
                  size="small"
                  :disabled="busy === inv.scopeId"
                  @click="decline(inv)"
                />
              </div>
            </div>
          </div>
        </div>
      </template>
    </section>
  </section>
</template>

<style scoped>
.settings-muted {
  margin: 0;
  color: var(--app-muted);
}
</style>
