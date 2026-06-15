<script setup lang="ts">
// A dismissible banner shown when an invite link is opened in a mobile browser: it offers to continue
// in the native veyq app via the veyq:// deep link (registration is nicer in-app, with biometric unlock
// and the Emergency Kit save flow). On iOS we additionally emit Apple's Smart App Banner meta tag.
import { onMounted, ref } from 'vue'
import { t } from '../../i18n/i18n'
import { buildInviteDeepLink, type ParsedInvite } from '../../features/spaces/invite/inviteLink'

const props = defineProps<{ invite: ParsedInvite }>()

const ua = navigator.userAgent
const isAndroid = /Android/i.test(ua)
const isIOS = /iPhone|iPad|iPod/i.test(ua)
const show = ref(isAndroid || isIOS)
const deepLink = buildInviteDeepLink(window.location.origin, props.invite)

onMounted(() => {
  // Apple's Smart App Banner — Safari renders this natively from the meta tag. app-id is a placeholder
  // until the iOS app is published; app-argument carries the deep link so the app opens on this invite.
  if (isIOS && !document.querySelector('meta[name="apple-itunes-app"]')) {
    const m = document.createElement('meta')
    m.name = 'apple-itunes-app'
    m.content = `app-id=000000000, app-argument=${deepLink}`
    document.head.appendChild(m)
  }
})
</script>

<template>
  <div v-if="show" class="open-in-app">
    <span class="open-in-app-text">{{ t('invite.openInApp') }}</span>
    <a class="btn btn-primary open-in-app-btn" :href="deepLink">{{ t('invite.openApp') }}</a>
    <button class="open-in-app-x" type="button" :aria-label="t('common.cancel')" @click="show = false">×</button>
  </div>
</template>
