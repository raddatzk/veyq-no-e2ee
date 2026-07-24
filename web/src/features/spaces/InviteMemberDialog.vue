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
import { DeliveryResult, inviteMember, memberError } from './members'
import { buildInviteLink, copyText } from '../../shared/inviteLink'
import { useAsyncAction } from '../../shared/state/useAsyncAction'

const props = defineProps<{ tenantId: string }>()
const visible = defineModel<boolean>('visible', { required: true })
const emit = defineEmits<{ invited: [] }>()
const { t } = useI18n()

const email = ref('')
const error = ref('')
const inviteUrl = ref('') // set on success → switches the dialog to the result view
const delivery = ref<DeliveryResult | ''>('') // how the invite went: SENT / FALLBACK_COPYLINK / FAILED
const sentTo = ref('') // the address we reported as invited, for the SENT headline
const showLink = ref(false) // on SENT the copy-link is tucked behind a toggle (mail is the primary channel)
const copied = ref(false)
const canSubmit = computed(() => email.value.trim().length > 0)

// Mail is the primary channel when SMTP is configured (SENT); the copy-link is the fallback. FAILED re-emphasises
// the link the response still carries; FALLBACK_COPYLINK is the unchanged self-hoster-without-SMTP copy-link path.
const isSent = computed(() => delivery.value === DeliveryResult.Sent)
const isFailed = computed(() => delivery.value === DeliveryResult.Failed)
const resultHeadline = computed(() =>
  isSent.value ? t('members.inviteSentTo', { email: sentTo.value })
    : isFailed.value ? t('members.deliveryFailed')
      : t('members.inviteCreated'),
)

watch(visible, (isVisible) => {
  if (isVisible) {
    error.value = ''
    email.value = ''
    inviteUrl.value = ''
    delivery.value = ''
    sentTo.value = ''
    showLink.value = false
    copied.value = false
  }
})

const { run: runSubmit, pending: loading } = useAsyncAction(
  async () => {
    const recipient = email.value.trim().toLowerCase()
    const state = await inviteMember(props.tenantId, recipient)
    delivery.value = state.delivery
    sentTo.value = recipient
    inviteUrl.value = buildInviteLink(state.bootstrapSecret) // same host — a tenant owner invites into their tenant
    emit('invited') // refresh the directory behind the dialog while the owner copies the link
  },
  {
    onError: async (e) => {
      const { code } = await memberError(e)
      error.value =
        code === 'already_member'
          ? t('members.alreadyMember')
          : code === 'not_tenant_owner'
            ? t('members.notOwner')
            : code === 'rate_limited'
              ? t('members.inviteRateLimited')
              : t('members.inviteFailed')
    },
  },
)

async function submit() {
  if (!canSubmit.value) return
  error.value = ''
  await runSubmit()
}

async function copyLink() {
  copied.value = await copyText(inviteUrl.value)
}
</script>

<template>
  <Dialog v-model:visible="visible" :header="t('members.invite')" modal class="tenant-dialog" :closable="!loading" :close-on-escape="!loading">
    <form v-if="!inviteUrl" class="veyq-form" @submit.prevent="submit">
      <p class="dialog-intro">{{ t('members.inviteIntro') }}</p>
      <label>
        {{ t('members.email') }}
        <InputText v-model="email" type="email" autocomplete="email" required :disabled="loading" />
      </label>
      <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>
      <div class="veyq-dialog-action-row">
        <Button
          type="button"
          :label="t('common.cancel')"
          icon="pi pi-times"
          severity="secondary"
          outlined
          :disabled="loading"
          @click="visible = false"
        />
        <Button type="submit" :label="t('members.inviteSubmit')" icon="pi pi-send" :loading="loading" :disabled="!canSubmit" />
      </div>
    </form>

    <div v-else class="veyq-form">
      <Message :severity="isFailed ? 'warn' : 'success'" :closable="false">{{ resultHeadline }}</Message>
      <!-- SENT: mail is the channel, so the copy-link is optional (behind a toggle). FAILED / FALLBACK_COPYLINK:
           the link is the channel, shown with its intro. -->
      <Button
        v-if="isSent"
        type="button"
        text
        size="small"
        :icon="showLink ? 'pi pi-chevron-up' : 'pi pi-chevron-down'"
        :label="showLink ? t('members.hideLink') : t('members.showLink')"
        @click="showLink = !showLink"
      />
      <p v-else class="dialog-intro">{{ isFailed ? t('members.deliveryFailedIntro') : t('members.copyLinkIntro') }}</p>
      <div v-if="!isSent || showLink" class="invite-link-row">
        <InputText :model-value="inviteUrl" readonly class="invite-link-input" @focus="($event.target as HTMLInputElement).select()" />
        <Button
          :icon="copied ? 'pi pi-check' : 'pi pi-copy'"
          :aria-label="t('members.copyLink')"
          :title="copied ? t('members.copied') : t('members.copyLink')"
          @click="copyLink"
        />
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

</style>
