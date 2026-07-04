<script setup lang="ts">
// Invite a person into the active tenant as a MEMBER (uc.spaces.add-member, cr-0077). Mirrors the create-tenant
// dialog structure; the data layer is the operator-blind member client. Admission only — the invitee onboards
// by email and joins grant-less; a key-holder delivers the scope key afterward (cr-0076), no action here.
// On success it shows the invite link to copy (cr-0080), so the owner can hand it off without a mailer.
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import { inviteMember, memberError } from './members'
import { buildInviteLink, copyText } from '../../shared/inviteLink'

const props = defineProps<{ tenantId: string }>()
const visible = defineModel<boolean>('visible', { required: true })
const emit = defineEmits<{ invited: [] }>()
const { t } = useI18n()

const email = ref('')
const error = ref('')
const loading = ref(false)
const inviteUrl = ref('') // set on success → switches the dialog to the copy-link view
const copied = ref(false)
const canSubmit = computed(() => email.value.trim().length > 0)

watch(visible, (isVisible) => {
  if (isVisible) {
    error.value = ''
    email.value = ''
    inviteUrl.value = ''
    copied.value = false
  }
})

async function submit() {
  if (!canSubmit.value) return
  error.value = ''
  loading.value = true
  try {
    const state = await inviteMember(props.tenantId, email.value.trim().toLowerCase())
    inviteUrl.value = buildInviteLink(state.bootstrapSecret) // same host — a tenant owner invites into their tenant
    emit('invited') // refresh the directory behind the dialog while the owner copies the link
  } catch (e) {
    const { code } = await memberError(e)
    error.value =
      code === 'already_member'
        ? t('members.alreadyMember')
        : code === 'not_tenant_owner'
          ? t('members.notOwner')
          : t('members.inviteFailed')
  } finally {
    loading.value = false
  }
}

async function copyLink() {
  copied.value = await copyText(inviteUrl.value)
}
</script>

<template>
  <Dialog v-model:visible="visible" :header="t('members.invite')" modal class="tenant-dialog">
    <form v-if="!inviteUrl" class="veyq-form" @submit.prevent="submit">
      <p class="dialog-intro">{{ t('members.inviteIntro') }}</p>
      <label>
        {{ t('members.email') }}
        <InputText v-model="email" type="email" autocomplete="email" required />
      </label>
      <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>
      <div class="veyq-dialog-action-row">
        <Button
          type="button"
          :label="t('common.cancel')"
          icon="pi pi-times"
          severity="secondary"
          outlined
          @click="visible = false"
        />
        <Button type="submit" :label="t('members.inviteSubmit')" icon="pi pi-send" :loading="loading" :disabled="!canSubmit" />
      </div>
    </form>

    <div v-else class="veyq-form">
      <Message severity="success" :closable="false">{{ t('members.inviteCreated') }}</Message>
      <p class="dialog-intro">{{ t('members.copyLinkIntro') }}</p>
      <div class="invite-link-row">
        <InputText :model-value="inviteUrl" readonly class="invite-link-input" @focus="($event.target as HTMLInputElement).select()" />
        <Button :label="copied ? t('members.copied') : t('members.copyLink')" :icon="copied ? 'pi pi-check' : 'pi pi-copy'" @click="copyLink" />
      </div>
      <div class="veyq-dialog-action-row">
        <Button type="button" :label="t('common.done')" icon="pi pi-check" @click="visible = false" />
      </div>
    </div>
  </Dialog>
</template>

<style scoped>
.dialog-intro {
  margin: 0 0 0.5rem;
  color: var(--app-muted);
  font-size: 0.9em;
}
.invite-link-row {
  display: flex;
  gap: 0.5rem;
  align-items: center;
}
.invite-link-input {
  flex: 1;
  min-width: 0;
  font-family: var(--font-mono, ui-monospace, monospace);
  font-size: 0.85em;
}
</style>
