<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { applyTheme, session } from './app/session'
import { t } from './i18n/i18n'
import { getSetupStatus } from './features/account/instance/instance'
import SetupPanel from './app/auth/SetupPanel.vue'
import LoginPanel from './app/auth/LoginPanel.vue'
import AcceptInvitePanel from './app/auth/AcceptInvitePanel.vue'
import ShareView from './app/share/ShareView.vue'
import HomeView from './app/home/HomeView.vue'
import { parseInviteHash, type InviteParts } from './features/spaces/invite/inviteLink'

// A public share link is `/s/<shareId>#k=<shareKey>` — viewable with no account.
const sharePath = /^\/s\/([^/]+)$/.exec(window.location.pathname)
const share = sharePath ? { shareId: sharePath[1], shareKey: new URLSearchParams(window.location.hash.slice(1)).get('k') ?? '' } : null

// Slice 1 (web): same-origin instance → on boot check setup status → setup (first run) or login →
// landed. An invite link (#invite=…&s=…&k=…) shows the accept flow instead.
applyTheme(session.theme)

type Phase = 'loading' | 'setup' | 'login' | 'error'
const phase = ref<Phase>('loading')
const prefill = ref<{ email?: string; secretKey?: string }>({})
const invite = ref<InviteParts | null>(parseInviteHash(window.location.hash))

onMounted(async () => {
  try {
    const status = await getSetupStatus(session.apiBase)
    phase.value = status.setupRequired ? 'setup' : 'login'
  } catch {
    phase.value = 'error'
  }
})

function onSetupDone(email: string, secretKey: string) {
  prefill.value = { email, secretKey }
  phase.value = 'login'
}

function clearInvite() {
  invite.value = null
  history.replaceState(null, '', window.location.pathname + window.location.search)
}
</script>

<template>
  <ShareView v-if="share" :share-id="share.shareId" :share-key="share.shareKey" />
  <AcceptInvitePanel v-else-if="invite" :invite="invite" @done="clearInvite" @dismiss="clearInvite" />
  <HomeView v-else-if="session.unlocked" />
  <div v-else-if="phase === 'loading'" class="auth-screen">
    <div class="auth-card"><p class="auth-wordmark">veyq</p><p class="auth-sub">{{ t('app.loading') }}</p></div>
  </div>
  <div v-else-if="phase === 'error'" class="auth-screen">
    <div class="auth-card">
      <p class="auth-wordmark">veyq</p>
      <h1 class="auth-title">{{ t('app.unreachableTitle') }}</h1>
      <p class="auth-sub">{{ t('app.unreachableSub') }}</p>
    </div>
  </div>
  <SetupPanel v-else-if="phase === 'setup'" @done="onSetupDone" />
  <LoginPanel v-else :prefill="prefill" />
</template>
