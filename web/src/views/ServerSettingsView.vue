<script setup lang="ts">
// Server Settings (cr-0050) — the Server Owner's instance-administration page, reached from the ProfileMenu and
// rendered inside the app shell (full width). Ported from the design source's SettingsView shell (the veyq-page
// container); its first and currently only rubric is "Manage tenants" (TenantAdminPanel). The other legacy
// rubrics (server info, users, license, support, update log) arrive as their backends land — the page is the
// container for them. Operator-blind: only operational metadata; the backend enforces Server-Owner with a 403,
// which the panel maps to a "not authorized" state.
import { useI18n } from 'vue-i18n'
import TenantAdminPanel from '../features/admin/createTenant/TenantAdminPanel.vue'

const { t } = useI18n()
</script>

<template>
  <div class="veyq-page veyq-page-narrow settings-page">
    <header class="tenant-settings-header">
      <h1>{{ t('shell.serverSettings') }}</h1>
      <p>{{ t('shell.serverSettingsSub') }}</p>
    </header>
    <TenantAdminPanel />
  </div>
</template>
