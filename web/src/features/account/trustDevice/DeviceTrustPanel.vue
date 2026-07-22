<script setup lang="ts">
// The "This device" section of the settings hub (cr-untrust-device-button) — device trust, uc.account.trust-device
// § Untrust. Shows whether THIS device is trusted (a DEVICE wrap that unlocks with just the password) and lets the
// user untrust it: delete the server DEVICE wrap + the local device key, so unlock here falls back to the PASSWORD
// floor + Secret Key. Device-local + operator-blind — reuses the already-built trustDevice logic, never re-encrypts
// content, and (endpoint whitelist) can only ever touch DEVICE wraps, never the 2SKD floor or a recovery path.
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import Message from 'primevue/message'
import { isTrusted, untrustThisDevice, isTrustSupported } from './trustDevice'
import { account } from '../../../app/account'
import { useAsyncAction } from '../../../shared/state/useAsyncAction'

const { t } = useI18n()

const supported = isTrustSupported()
const trusted = ref(false)
const confirmOpen = ref(false)
const error = ref('')

// Reflect the local trust record for the signed-in account. Best-effort: any read error reads as "not trusted"
// (the safe default — it only ever offers the untrust action when we positively know a wrap is here).
async function refresh() {
  trusted.value = account.email ? await isTrusted(account.email).catch(() => false) : false
}
onMounted(refresh)

const { run: runUntrust, pending: busy } = useAsyncAction(
  async () => {
    await untrustThisDevice(account.email)
    await refresh()
    confirmOpen.value = false
  },
  { onError: () => { error.value = t('settings.untrustFailed') } },
)

async function confirmUntrust() {
  error.value = ''
  await runUntrust()
}
</script>

<template>
  <section class="veyq-surface settings-panel">
    <section class="settings-form">
      <div class="settings-section-header">
        <div>
          <h2>{{ t('settings.deviceTitle') }}</h2>
          <p>{{ t('settings.deviceSub') }}</p>
        </div>
      </div>

      <p class="device-status">
        <i
          class="pi"
          :class="!supported ? 'pi-info-circle' : trusted ? 'pi-shield' : 'pi-lock'"
          aria-hidden="true"
        />
        <span>{{
          !supported
            ? t('settings.deviceUnsupported')
            : trusted
              ? t('settings.deviceTrusted')
              : t('settings.deviceUntrusted')
        }}</span>
      </p>

      <div v-if="supported && trusted" class="device-action">
        <Button
          type="button"
          :label="t('unlock.untrust')"
          icon="pi pi-lock"
          severity="danger"
          outlined
          @click="confirmOpen = true"
        />
      </div>
    </section>

    <Dialog
      v-model:visible="confirmOpen"
      :header="t('settings.untrustConfirmTitle')"
      modal
      :closable="!busy"
      :close-on-escape="!busy"
    >
      <p class="dialog-copy">{{ t('settings.untrustConfirmBody') }}</p>
      <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>
      <div class="veyq-dialog-action-row">
        <Button type="button" :label="t('common.cancel')" icon="pi pi-times" severity="secondary" outlined :disabled="busy" @click="confirmOpen = false" />
        <Button type="button" :label="t('unlock.untrust')" icon="pi pi-lock" severity="danger" :loading="busy" @click="confirmUntrust" />
      </div>
    </Dialog>
  </section>
</template>

<style scoped>
.device-status {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  margin: 0.5rem 0 1rem;
}
.device-status .pi {
  color: var(--app-muted);
}
.device-action {
  display: flex;
}
.dialog-copy {
  margin: 0 0 0.75rem;
}
</style>
