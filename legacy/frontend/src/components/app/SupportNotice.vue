<script setup lang="ts">
// A dismissible "support this instance" banner. The operator sets veyq.app.support-url; the server surfaces
// it on /api/setup as supportUrl. Dismissal is client-side (localStorage) and re-shows after 90 days, keyed
// by server version so a new release can re-surface it. Low-sensitivity, same-origin config only.
import { onMounted, ref } from 'vue'
import Button from 'primevue/button'
import { getSetupStatus } from '../../features/account/instance/instance'
import { session } from '../../app/session'
import { t } from '../../i18n/i18n'

const SUPPORT_NOTICE_MS = 90 * 24 * 60 * 60 * 1000

const supportUrl = ref('')
const visible = ref(false)

onMounted(load)

async function load() {
  try {
    const status = await getSetupStatus(session.apiBase)
    const key = storageKey(status.serverVersion)
    supportUrl.value = status.supportUrl ?? ''
    visible.value = Boolean(supportUrl.value) && shouldShow(key)
  } catch {
    visible.value = false
  }
}

function dismiss() {
  if (!supportUrl.value) return
  window.localStorage.setItem(storageKey(currentVersion.value), String(Date.now()))
  visible.value = false
}

const currentVersion = ref('')
function storageKey(version?: string): string {
  currentVersion.value = version || 'unversioned'
  return `veyq:support-notice-dismissed:${currentVersion.value}`
}

function shouldShow(key: string): boolean {
  const dismissedAt = Number(window.localStorage.getItem(key) || 0)
  return !dismissedAt || Date.now() - dismissedAt >= SUPPORT_NOTICE_MS
}
</script>

<template>
  <section v-if="visible" class="support-notice" aria-live="polite">
    <div>
      <strong>{{ t('support.noticeTitle') }}</strong>
      <span>{{ t('support.noticeCopy') }}</span>
    </div>
    <div class="support-notice-actions">
      <a class="veyq-support-button" :href="supportUrl" target="_blank" rel="noreferrer">
        <span class="pi pi-heart" />
        <span>{{ t('support.action') }}</span>
      </a>
      <Button type="button" severity="secondary" text size="small" :label="t('support.dismiss')" @click="dismiss" />
    </div>
  </section>
</template>
