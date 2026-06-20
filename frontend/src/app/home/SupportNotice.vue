<script setup lang="ts">
import { computed, ref } from 'vue'
import { session } from '../session'
import { t } from '../../i18n/i18n'

const SUPPORT_URL = 'https://github.com/sponsors/raddatzk'
const STORAGE_KEY = 'veyq.supportNotice.dismissed.v1'

const dismissed = ref(localStorage.getItem(STORAGE_KEY) === 'true')
const visible = computed(() => session.unlocked?.user.serverRole === 'ServerOwner' && !dismissed.value)

function dismiss() {
  dismissed.value = true
  localStorage.setItem(STORAGE_KEY, 'true')
}
</script>

<template>
  <section v-if="visible" class="support-notice" aria-live="polite">
    <div>
      <strong>{{ t('support.noticeTitle') }}</strong>
      <span>{{ t('support.noticeCopy') }}</span>
    </div>
    <div class="support-notice-actions">
      <a class="veyq-support-button" :href="SUPPORT_URL" target="_blank" rel="noreferrer">
        <span class="support-heart" aria-hidden="true"></span>
        <span>{{ t('support.action') }}</span>
      </a>
      <button class="support-dismiss" type="button" @click="dismiss">{{ t('support.dismiss') }}</button>
    </div>
  </section>
</template>
