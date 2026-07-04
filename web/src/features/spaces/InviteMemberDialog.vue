<script setup lang="ts">
// Invite a person into the active tenant as a MEMBER (uc.spaces.add-member, cr-0077). Mirrors the create-tenant
// dialog structure; the data layer is the operator-blind member client. Admission only — the invitee onboards
// by email and joins grant-less; a key-holder delivers the scope key afterward (cr-0076), no action here.
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import { inviteMember, memberError } from './members'

const props = defineProps<{ tenantId: string }>()
const visible = defineModel<boolean>('visible', { required: true })
const emit = defineEmits<{ invited: [] }>()
const { t } = useI18n()

const email = ref('')
const error = ref('')
const loading = ref(false)
const canSubmit = computed(() => email.value.trim().length > 0)

watch(visible, (isVisible) => {
  if (isVisible) {
    error.value = ''
    email.value = ''
  }
})

async function submit() {
  if (!canSubmit.value) return
  error.value = ''
  loading.value = true
  try {
    await inviteMember(props.tenantId, email.value.trim().toLowerCase())
    visible.value = false
    emit('invited')
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
</script>

<template>
  <Dialog v-model:visible="visible" :header="t('members.invite')" modal class="tenant-dialog">
    <form class="veyq-form" @submit.prevent="submit">
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
  </Dialog>
</template>

<style scoped>
.dialog-intro {
  margin: 0 0 0.5rem;
  color: var(--app-muted);
  font-size: 0.9em;
}
</style>
