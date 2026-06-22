<script setup lang="ts">
// Settings dispatcher for the E2EE backend. The legacy hierarchical `getSettings(path)` resolution is gone;
// we render purely by route + the active space:
//   /~me/settings     → user profile + devices (self-contained)
//   /server/settings  → server admin (ServerOwner only)
//   /space/settings   → the active space's admin (TENANT → tenant panel, GROUP → group panel)
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import Message from 'primevue/message'
import GroupSettingsPanel from '../features/settings/components/GroupSettingsPanel.vue'
import ServerSettingsPanel from '../features/settings/components/ServerSettingsPanel.vue'
import TenantSettingsPanel from '../features/settings/components/TenantSettingsPanel.vue'
import UserSettingsForm from '../features/settings/components/UserSettingsForm.vue'
import { session } from '../app/session'

const route = useRoute()
const { t } = useI18n()

const isUserSettings = computed(() => route.name === 'user-settings')
const isServerSettings = computed(() => route.name === 'server-settings')
const isSpaceSettings = computed(() => route.name === 'space-settings')

const isServerOwner = computed(() => session.unlocked?.user.serverRole === 'ServerOwner')
const activeEntry = computed(() => session.activeSpace?.entry ?? null)
const activeScopeId = computed(() => session.activeSpace?.scope.scopeId ?? '')
const activeKind = computed(() => activeEntry.value?.kind ?? null)
const activeTenantId = computed(() => activeEntry.value?.tenantId ?? '')
</script>

<template>
  <div class="veyq-page veyq-page-narrow settings-page">
    <UserSettingsForm v-if="isUserSettings" />

    <template v-else-if="isServerSettings">
      <ServerSettingsPanel v-if="isServerOwner" />
      <Message v-else severity="warn" :closable="false">{{ t('settings.notAuthorized') }}</Message>
    </template>

    <template v-else-if="isSpaceSettings">
      <TenantSettingsPanel v-if="activeKind === 'TENANT'" :tenant-id="activeTenantId" :scope-id="activeScopeId" />
      <GroupSettingsPanel v-else-if="activeKind === 'GROUP'" :tenant-id="activeTenantId" :scope-id="activeScopeId" />
      <Message v-else severity="info" :closable="false">{{ t('settings.noSpaceSettings') }}</Message>
    </template>

    <Message v-else severity="info" :closable="false">{{ t('settings.title') }}</Message>
  </div>
</template>
