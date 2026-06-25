<script setup lang="ts">
// Server admin (Server Owner only): the instance roster + server-role assignment. Operator-blind — the
// backend returns only id/email/displayName/serverRole. Promote a member to Server Owner (gates tenant
// creation) or demote back; the backend keeps at least one owner.
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import Message from 'primevue/message'
import ProgressSpinner from 'primevue/progressspinner'
import { listServerUsers, setServerRole, type ServerRole, type ServerUser } from '../../admin/serverUsers/serverUsers'
import { requireSession, session } from '../../../app/session'

const { t } = useI18n()
const users = ref<ServerUser[]>([])
const loading = ref(false)
const savingId = ref<string | null>(null)
const error = ref('')
const message = ref('')

onMounted(load)

async function load() {
  loading.value = true
  error.value = ''
  try {
    users.value = await listServerUsers(session.apiBase, requireSession())
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : t('settings.serverLoadFailed')
  } finally {
    loading.value = false
  }
}

async function changeRole(user: ServerUser, role: ServerRole) {
  if (role === user.serverRole) return
  savingId.value = user.id
  error.value = ''
  message.value = ''
  try {
    const updated = await setServerRole(session.apiBase, requireSession(), user.id, role)
    users.value = users.value.map((u) => (u.id === updated.id ? updated : u))
    message.value = t('common.saved')
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : t('settings.loadFailed')
  } finally {
    savingId.value = null
  }
}
</script>

<template>
  <section class="veyq-surface settings-panel">
    <h1>{{ t('settings.serverSettings') }}</h1>

    <section class="settings-section">
      <div class="settings-section-header">
        <h2>{{ t('settings.serverUsers') }}</h2>
        <p>{{ t('settings.serverUsersCopy') }}</p>
      </div>

      <div v-if="loading" class="veyq-loading">
        <ProgressSpinner class="small-spinner" />
        <span>{{ t('settings.serverLoading') }}</span>
      </div>

      <table v-else class="server-users-table">
        <tbody>
          <tr v-for="user in users" :key="user.id">
            <td>
              <strong>{{ user.displayName }}</strong>
              <small>{{ user.email }}</small>
            </td>
            <td class="server-users-role">
              <select
                :value="user.serverRole"
                :disabled="savingId === user.id"
                @change="changeRole(user, ($event.target as HTMLSelectElement).value as ServerRole)"
              >
                <option value="ServerOwner">{{ t('settings.serverRoles.ServerOwner') }}</option>
                <option value="ServerMember">{{ t('settings.serverRoles.ServerMember') }}</option>
              </select>
            </td>
          </tr>
        </tbody>
      </table>

      <Message v-if="message" severity="success" :closable="false">{{ message }}</Message>
      <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>
    </section>
  </section>
</template>

<style scoped>
.settings-section { margin-top: 1rem; }
.server-users-table { width: 100%; border-collapse: collapse; }
.server-users-table td { padding: 0.6rem 0; border-bottom: 1px solid var(--p-content-border-color, rgba(127,127,127,0.15)); vertical-align: middle; }
.server-users-table strong { display: block; }
.server-users-table small { opacity: 0.6; }
.server-users-role { text-align: right; width: 12rem; }
.server-users-role select {
  padding: 0.4rem 0.6rem;
  border-radius: 8px;
  border: 1px solid var(--p-content-border-color, rgba(127,127,127,0.25));
  background: var(--p-content-background, transparent);
  color: inherit;
}
</style>
