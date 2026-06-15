<script setup lang="ts">
import { ref } from 'vue'
import { session } from '../session'
import { t } from '../../i18n/i18n'
import { inviteToGroup } from '../../features/spaces/invite/groups'
import type { MemberRole } from '../../features/admin/inviteMembersRoles/inviteMembers'
import type { SpaceEntry } from '../../features/home/home'

const props = defineProps<{ space: SpaceEntry }>()
const emit = defineEmits<{ close: [] }>()

const role = ref<MemberRole>('Member')
const link = ref('')
const busy = ref(false)
const copied = ref(false)
const error = ref('')

async function generate() {
  if (!session.unlocked || !props.space.tenantId) return
  busy.value = true
  error.value = ''
  try {
    link.value = await inviteToGroup(session.apiBase, session.unlocked, props.space.tenantId, role.value, window.location.origin)
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
</script>

<template>
  <div class="settings-backdrop" @click.self="emit('close')">
    <div class="settings-panel">
      <div class="settings-head">
        <h2 class="settings-title">{{ t('invite.title') }}</h2>
        <button class="btn btn-ghost" type="button" @click="emit('close')">{{ t('common.close') }}</button>
      </div>
      <div class="settings-section">{{ space.displayName }}</div>

      <label class="invite-role">
        {{ t('invite.role') }}
        <div class="seg">
          <button type="button" :class="{ active: role === 'Member' }" @click="role = 'Member'">{{ t('invite.member') }}</button>
          <button type="button" :class="{ active: role === 'Owner' }" @click="role = 'Owner'">{{ t('invite.owner') }}</button>
        </div>
      </label>

      <div v-if="error" class="auth-msg auth-msg-error">{{ error }}</div>

      <template v-if="link">
        <p class="invite-hint">{{ t('invite.link') }}</p>
        <div class="invite-link-row">
          <input class="invite-link" :value="link" readonly @focus="($event.target as HTMLInputElement).select()" />
          <button class="btn btn-primary" type="button" @click="copy">{{ copied ? t('invite.copied') : t('invite.copy') }}</button>
        </div>
      </template>
      <button v-else class="btn btn-primary invite-gen" type="button" :disabled="busy" @click="generate">{{ t('invite.generate') }}</button>
    </div>
  </div>
</template>
