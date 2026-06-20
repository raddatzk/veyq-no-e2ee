<script setup lang="ts">
// Server-user administration (Server Owner only): the instance roster with a toggle to promote a member to
// Server Owner (the role that gates tenant creation) or demote back. Operator-blind — only account fields.
import { ref, watch } from 'vue'
import Button from 'primevue/button'
import { listServerUsers, setServerRole, type ServerUser } from '../../../features/admin/serverUsers/serverUsers'
import { session } from '../../../app/session'
import { t } from '../../../i18n/i18n'

const users = ref<ServerUser[]>([])
const error = ref('')
const busy = ref('')

async function load() {
  error.value = ''
  try {
    users.value = await listServerUsers(session.apiBase, session.unlocked!)
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  }
}

async function toggle(u: ServerUser) {
  busy.value = u.id
  error.value = ''
  try {
    await setServerRole(session.apiBase, session.unlocked!, u.id, u.serverRole === 'ServerOwner' ? 'ServerMember' : 'ServerOwner')
    await load()
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    busy.value = ''
  }
}

watch(() => session.unlocked?.user.id, load, { immediate: true })
</script>

<template>
  <div class="server-users">
    <p v-if="error" class="server-users-error">{{ error }}</p>
    <ul class="user-list">
      <li v-for="u in users" :key="u.id" class="user-row">
        <span class="user-meta">
          <strong>{{ u.displayName }}<span v-if="u.id === session.unlocked?.user.id" class="user-you"> ({{ t('members.you') }})</span></strong>
          <small>{{ u.email }} · {{ u.serverRole === 'ServerOwner' ? 'Server Owner' : 'Server Member' }}</small>
        </span>
        <Button
          type="button"
          :label="u.serverRole === 'ServerOwner' ? t('invite.member') : t('invite.owner')"
          severity="secondary"
          text
          size="small"
          :loading="busy === u.id"
          @click="toggle(u)"
        />
      </li>
    </ul>
  </div>
</template>

<style scoped>
.server-users { margin: 0.5rem 0; }
.server-users-error { color: var(--p-red-400, #f87171); }
.user-list { list-style: none; padding: 0; margin: 0; display: flex; flex-direction: column; gap: 0.4rem; }
.user-row { display: flex; align-items: center; justify-content: space-between; gap: 0.5rem; }
.user-meta { display: flex; flex-direction: column; }
.user-meta small { opacity: 0.6; }
.user-you { opacity: 0.6; font-weight: 400; }
</style>
