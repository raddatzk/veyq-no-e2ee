<script setup lang="ts">
// A dismissible "open in the native app" banner shown in a mobile browser. With an `invite`, it deep-links
// straight into the invite flow (registration is nicer in-app); without one, it's the general nudge for a
// signed-in user to switch to the app (`veyq://open`). On iOS we also emit Apple's Smart App Banner meta tag.
import { computed, onMounted, ref } from 'vue'
import { t } from '../../i18n/i18n'
import { buildInviteDeepLink, type ParsedInvite } from '../../features/spaces/invite/inviteLink'

const props = defineProps<{ invite?: ParsedInvite; persistKey?: string; path?: string }>()

const ua = navigator.userAgent
const isAndroid = /Android/i.test(ua)
const isIOS = /iPhone|iPad|iPod/i.test(ua)
// `persistKey` remembers a dismissal across reloads (used for the always-present home banner; the invite
// banner has no key, so it reappears each visit).
const dismissed = props.persistKey ? !!localStorage.getItem(`veyq.dismiss.${props.persistKey}`) : false
const show = ref((isAndroid || isIOS) && !dismissed)
const origin = window.location.origin.replace(/\/$/, '')
// The generic deep link carries the current in-app path (`u`) so the app opens the exact memory/folder; it
// updates reactively as the user navigates. The instance origin rides in `api` (the app faces many instances).
const deepLink = computed(() =>
  props.invite
    ? buildInviteDeepLink(origin, props.invite)
    : `veyq://open?u=${encodeURIComponent(props.path ?? '/')}&api=${encodeURIComponent(origin)}`,
)
const label = props.invite ? t('invite.openInApp') : t('app.openInApp')

function dismiss() {
  show.value = false
  if (props.persistKey) localStorage.setItem(`veyq.dismiss.${props.persistKey}`, '1')
}

onMounted(() => {
  // Apple's Smart App Banner — Safari renders this natively from the meta tag. app-id is a placeholder
  // until the iOS app is published; app-argument carries the deep link so the app opens on the right target.
  if (isIOS && !document.querySelector('meta[name="apple-itunes-app"]')) {
    const m = document.createElement('meta')
    m.name = 'apple-itunes-app'
    m.content = `app-id=000000000, app-argument=${deepLink.value}`
    document.head.appendChild(m)
  }
})
</script>

<template>
  <div v-if="show" class="open-in-app">
    <span class="open-in-app-text">{{ label }}</span>
    <a class="btn btn-primary open-in-app-btn" :href="deepLink">{{ t('invite.openApp') }}</a>
    <button class="open-in-app-x" type="button" :aria-label="t('common.cancel')" @click="dismiss">×</button>
  </div>
</template>
