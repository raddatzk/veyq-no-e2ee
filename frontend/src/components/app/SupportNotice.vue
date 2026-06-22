<script setup lang="ts">
import { onMounted, ref } from 'vue'
import Button from 'primevue/button'
import { useI18n } from 'vue-i18n'
import { getServerSettings, type ServerSettingsResponse } from '../../features/settings/api'

const SUPPORT_NOTICE_DAYS = 90
const SUPPORT_NOTICE_MS = SUPPORT_NOTICE_DAYS * 24 * 60 * 60 * 1000

const { t } = useI18n()
const settings = ref<ServerSettingsResponse | null>(null)
const visible = ref(false)

onMounted(loadSupportNotice)

async function loadSupportNotice() {
  try {
    const response = await getServerSettings()
    settings.value = response
    visible.value = Boolean(response.support?.url) && shouldShow(response)
  } catch {
    settings.value = null
    visible.value = false
  }
}

function dismiss() {
  const current = settings.value
  if (!current) return
  window.localStorage.setItem(storageKey(current), String(Date.now()))
  visible.value = false
}

function shouldShow(response: ServerSettingsResponse): boolean {
  const dismissedAt = Number(window.localStorage.getItem(storageKey(response)) || 0)
  return !dismissedAt || Date.now() - dismissedAt >= SUPPORT_NOTICE_MS
}

function storageKey(response: ServerSettingsResponse): string {
  const versionKey = response.license.version || response.license.revision || 'unversioned'
  return `veyq:support-notice-dismissed:${versionKey}`
}
</script>

<template>
  <section v-if="visible && settings?.support" class="support-notice" aria-live="polite">
    <div>
      <strong>{{ t('support.noticeTitle') }}</strong>
      <span>{{ t('support.noticeCopy') }}</span>
    </div>
    <div class="support-notice-actions">
      <a class="veyq-support-button" :href="settings.support.url" target="_blank" rel="noreferrer">
        <span class="pi pi-heart" />
        <span>{{ t('support.action') }}</span>
      </a>
      <Button
        type="button"
        severity="secondary"
        text
        size="small"
        :label="t('support.dismiss')"
        @click="dismiss"
      />
    </div>
  </section>
</template>
