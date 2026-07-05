<script setup lang="ts">
// The settings hub (cr-0078, ADR 0022) — one scope-contextual page reached from the top-bar gear. A responsive
// NavigationSplitView: wide viewports show the section sidebar + detail side by side; narrow viewports collapse
// to a list that drills into /settings/<section>. Sections are filtered by the caller's scope path + role
// (Persönlich always; Tenant when in a tenant; Server for a Server Owner). Existing panels are re-homed here,
// not rewritten. Content-object settings (folders, memories) stay inline on their objects — never in the hub.
import { computed, onMounted, type Component } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import AppearancePanel from '../features/settings/AppearancePanel.vue'
import ProfilePanel from '../features/account/profile/ProfilePanel.vue'
import TenantMembersPanel from '../features/spaces/TenantMembersPanel.vue'
import TenantAdminPanel from '../features/admin/createTenant/TenantAdminPanel.vue'
import { activeSpace } from '../app/activeSpace'
import { serverRole, probeServerRole } from '../features/admin/serverRole'

type Section = { id: string; group: string; label: string; icon: string; component: Component }

const { t } = useI18n()
const route = useRoute()
const router = useRouter()

onMounted(() => void probeServerRole())

// Scope × role filtered: a section appears only when the caller can see or do something there.
const sections = computed<Section[]>(() => {
  const list: Section[] = [
    { id: 'appearance', group: t('settings.personal'), label: t('settings.appearance'), icon: 'pi pi-palette', component: AppearancePanel },
  ]
  // Your per-tenant identity (username + real name) — needs a tenant context (ADR 0023).
  if (activeSpace.tenantId) {
    list.push({ id: 'profile', group: t('settings.personal'), label: t('profile.title'), icon: 'pi pi-user', component: ProfilePanel })
    list.push({ id: 'tenant-members', group: t('settings.tenant'), label: t('members.title'), icon: 'pi pi-users', component: TenantMembersPanel })
  }
  if (serverRole.canManage) {
    list.push({ id: 'server', group: t('settings.server'), label: t('settings.serverTenants'), icon: 'pi pi-server', component: TenantAdminPanel })
  }
  return list
})

// The nav grouped by scope level (each level can hold several sections later, e.g. Tenant → Mitglieder + …).
const groups = computed(() => {
  const order: string[] = []
  const byGroup = new Map<string, Section[]>()
  for (const s of sections.value) {
    if (!byGroup.has(s.group)) { byGroup.set(s.group, []); order.push(s.group) }
    byGroup.get(s.group)!.push(s)
  }
  return order.map((name) => ({ name, items: byGroup.get(name)! }))
})

// Default (wide, no param) opens the current scope's section; narrow with no param shows the list only.
const defaultId = computed(() => (activeSpace.tenantId ? 'tenant-members' : 'appearance'))
const activeId = computed(() => (route.params.section as string | undefined) || defaultId.value)
const active = computed(() => sections.value.find((s) => s.id === activeId.value) ?? sections.value[0])
const hasSection = computed(() => Boolean(route.params.section))

const open = (id: string) => router.push(`/settings/${id}`)
const back = () => router.push('/settings')
</script>

<template>
  <div class="veyq-page settings-hub-page">
    <h1 class="settings-hub-title">{{ t('settings.title') }}</h1>
    <div class="settings-hub" :class="{ 'is-index': !hasSection, 'is-detail': hasSection }">
      <nav class="settings-nav" :aria-label="t('settings.title')">
        <template v-for="g in groups" :key="g.name">
          <p class="settings-nav-group">{{ g.name }}</p>
          <button
            v-for="s in g.items"
            :key="s.id"
            type="button"
            class="settings-nav-item"
            :class="{ 'is-active': s.id === active?.id }"
            @click="open(s.id)"
          >
            <i :class="s.icon" aria-hidden="true" />
            <span>{{ s.label }}</span>
          </button>
        </template>
      </nav>

      <section class="settings-detail">
        <button type="button" class="settings-back" @click="back">
          <i class="pi pi-chevron-left" aria-hidden="true" />
          {{ t('settings.back') }}
        </button>
        <component :is="active?.component" v-if="active" />
      </section>
    </div>
  </div>
</template>

<style scoped>
.settings-hub-page {
  max-width: 960px;
}
.settings-hub-title {
  margin: 0 0 1rem;
}
.settings-hub {
  display: flex;
  gap: 1.5rem;
  align-items: flex-start;
}
.settings-nav {
  flex: 0 0 220px;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.settings-nav-group {
  margin: 0.75rem 0.5rem 0.25rem;
  font-size: 0.8rem;
  font-weight: 500;
  color: var(--app-muted, var(--p-text-muted-color));
}
.settings-nav-item {
  display: flex;
  align-items: center;
  gap: 0.6rem;
  padding: 0.5rem 0.6rem;
  border: none;
  border-radius: 0.5rem;
  background: transparent;
  color: inherit;
  font: inherit;
  text-align: left;
  cursor: pointer;
}
.settings-nav-item:hover {
  background: var(--p-content-hover-background, rgba(127, 127, 127, 0.08));
}
.settings-nav-item.is-active {
  background: var(--p-highlight-background, rgba(127, 127, 127, 0.14));
  font-weight: 500;
}
.settings-detail {
  flex: 1 1 auto;
  min-width: 0;
}
.settings-back {
  display: none;
  align-items: center;
  gap: 0.35rem;
  margin-bottom: 0.75rem;
  padding: 0.3rem 0.5rem;
  border: none;
  background: transparent;
  color: inherit;
  font: inherit;
  cursor: pointer;
}

@media (max-width: 720px) {
  .settings-nav {
    flex-basis: 100%;
    width: 100%;
  }
  .settings-hub.is-index .settings-detail {
    display: none;
  }
  .settings-hub.is-detail .settings-nav {
    display: none;
  }
  .settings-hub.is-detail .settings-back {
    display: inline-flex;
  }
}
</style>
