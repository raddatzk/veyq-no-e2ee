<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import Card from 'primevue/card'
import Message from 'primevue/message'
import { takeAuthPrefill } from '../app/authPrefill'

// Login STUB (uc deferred to the login CR). It only consumes the post-setup prefill (email + Secret Key
// handed over from setup) so the wiring is real and the future login view can drop in unchanged.
const { t } = useI18n()
const prefillEmail = ref('')

onMounted(() => {
  const prefill = takeAuthPrefill()
  if (prefill) prefillEmail.value = prefill.email
})
</script>

<template>
  <Card class="login-card">
    <template #title>{{ t('login.title') }}</template>
    <template #content>
      <Message severity="info" :closable="false">{{ t('login.stub') }}</Message>
      <p v-if="prefillEmail" class="veyq-form-copy" style="margin-top: 1rem">{{ prefillEmail }}</p>
    </template>
  </Card>
</template>
