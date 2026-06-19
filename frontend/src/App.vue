<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { applyTheme, session, setUnlocked } from './app/session'
import { t } from './i18n/i18n'
import { getSetupStatus } from './features/account/instance/instance'
import { login } from './features/account/login/login'
import { clearRememberedLogin, readRememberedLogin } from './features/account/rememberedLogin/rememberedLogin'
import SetupPanel from './app/auth/SetupPanel.vue'
import LoginPanel from './app/auth/LoginPanel.vue'
import AcceptInvitePanel from './app/auth/AcceptInvitePanel.vue'
import ResetPasswordPanel from './app/auth/ResetPasswordPanel.vue'
import ShareView from './app/share/ShareView.vue'
import HomeView from './app/home/HomeView.vue'
import { parseInviteHash, type ParsedInvite } from './features/spaces/invite/inviteLink'
import { parseResetHash } from './features/account/resetPassword/resetPassword'

// A public share link is `/s/<shareId>#k=<shareKey>` — viewable with no account.
const sharePath = /^\/s\/([^/]+)$/.exec(window.location.pathname)
const share = ref(sharePath ? { shareId: sharePath[1], shareKey: new URLSearchParams(window.location.hash.slice(1)).get('k') ?? '' } : null)
// When a logged-in member opens a share they can access, jump them into the full internal memory instead.
const homeTarget = ref<{ scopeId: string; nodeId: string } | null>(null)
function onShareInternal(target: { scopeId: string; nodeId: string }) {
  share.value = null
  homeTarget.value = target
  history.replaceState(null, '', '/')
}

// Slice 1 (web): same-origin instance → on boot check setup status → setup (first run) or login →
// landed. An invite link (#invite=…&s=…&k=…) shows the accept flow instead.
applyTheme(session.theme)

type Phase = 'loading' | 'setup' | 'login' | 'error'
const phase = ref<Phase>('loading')
const bootLoadingVisible = ref(false)
const prefill = ref<{ email?: string; secretKey?: string }>({})
const invite = ref<ParsedInvite | null>(parseInviteHash(window.location.hash))
const resetToken = ref<string | null>(parseResetHash(window.location.hash))
let bootLoadingTimer: number | null = null

function stopDelayedBootLoader() {
  if (bootLoadingTimer !== null) window.clearTimeout(bootLoadingTimer)
  bootLoadingTimer = null
  bootLoadingVisible.value = false
}

function startDelayedBootLoader() {
  stopDelayedBootLoader()
  bootLoadingTimer = window.setTimeout(() => {
    if (phase.value === 'loading' && !session.unlocked) bootLoadingVisible.value = true
  }, 220)
}

async function tryRememberedLogin(): Promise<boolean> {
  const creds = readRememberedLogin()
  if (!creds) return false
  try {
    setUnlocked(await login(session.apiBase, creds.email, creds.password, creds.secretKey))
    return true
  } catch {
    clearRememberedLogin()
    return false
  }
}

onMounted(async () => {
  startDelayedBootLoader()
  try {
    const status = await getSetupStatus(session.apiBase)
    if (status.setupRequired) {
      phase.value = 'setup'
      return
    }
    if (!share.value && !invite.value && !resetToken.value && await tryRememberedLogin()) return
    phase.value = 'login'
  } catch {
    phase.value = 'error'
  } finally {
    if (phase.value !== 'loading' || session.unlocked) stopDelayedBootLoader()
  }
})

onBeforeUnmount(stopDelayedBootLoader)

function onSetupDone(email: string, secretKey: string) {
  prefill.value = { email, secretKey }
  phase.value = 'login'
}

function clearInvite() {
  invite.value = null
  history.replaceState(null, '', window.location.pathname + window.location.search)
}

function clearReset() {
  resetToken.value = null
  history.replaceState(null, '', window.location.pathname + window.location.search)
}
</script>

<template>
  <ShareView v-if="share" :share-id="share.shareId" :share-key="share.shareKey" @internal="onShareInternal" />
  <ResetPasswordPanel v-else-if="resetToken && !session.unlocked" :token="resetToken" @done="clearReset" />
  <AcceptInvitePanel v-else-if="invite" :invite="invite" @done="clearInvite" @dismiss="clearInvite" />
  <HomeView v-else-if="session.unlocked" :open-target="homeTarget" />
  <div v-else-if="phase === 'loading'" class="auth-screen auth-boot-screen" :aria-label="t('app.loading')" aria-live="polite">
    <div v-if="bootLoadingVisible" class="auth-boot-loader">
      <p class="auth-wordmark">veyq</p>
      <span class="auth-boot-dot" aria-hidden="true"></span>
    </div>
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
