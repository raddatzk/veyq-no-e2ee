<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import { capabilityDescription, capabilityTitle } from '../../features/settings/capabilities'
import { dismissCapabilityNotification, getCapabilityNotifications } from '../../features/settings/api'
import type { CapabilityNotification } from '../../features/settings/api'

const notifications = ref<CapabilityNotification[]>([])
const open = ref(false)
const popoverRoot = ref<HTMLElement | null>(null)
const { t, te } = useI18n()
const notificationLabel = computed(() => t('notifications.title'))

onMounted(() => {
  document.addEventListener('pointerdown', handleDocumentPointerDown)
  document.addEventListener('keydown', handleDocumentKeydown)
  void loadNotifications()
})

onBeforeUnmount(() => {
  document.removeEventListener('pointerdown', handleDocumentPointerDown)
  document.removeEventListener('keydown', handleDocumentKeydown)
})

async function loadNotifications() {
  notifications.value = await getCapabilityNotifications().catch(() => [])
  if (!notifications.value.length) open.value = false
}

async function dismiss(capabilityId: string) {
  await dismissCapabilityNotification(capabilityId)
  notifications.value = notifications.value.filter((item) => item.capability.id !== capabilityId)
  if (!notifications.value.length) open.value = false
}

function toggleOpen() {
  open.value = !open.value
}

function closeOpenPopover() {
  open.value = false
}

function handleDocumentPointerDown(event: PointerEvent) {
  if (!open.value) return
  const root = popoverRoot.value
  if (root && event.target instanceof Node && !root.contains(event.target)) {
    closeOpenPopover()
  }
}

function handleDocumentKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') {
    closeOpenPopover()
  }
}
</script>

<template>
  <div v-if="notifications.length" ref="popoverRoot" class="notification-popover">
    <button
      type="button"
      class="notification-trigger"
      :class="{ 'notification-trigger-active': open }"
      :aria-label="notificationLabel"
      :title="notificationLabel"
      @click="toggleOpen"
    >
      <span class="pi pi-envelope" />
      <span class="notification-badge">{{ notifications.length }}</span>
    </button>
    <section v-if="open" class="notification-panel" aria-live="polite">
      <header class="notification-header">
        <div>
          <h3>{{ t('notifications.title') }}</h3>
          <p>{{ t('notifications.count', { count: notifications.length }) }}</p>
        </div>
      </header>
      <ul class="notification-list">
        <li
          v-for="notification in notifications"
          :key="notification.capability.id"
          class="notification-item"
        >
          <div class="notification-item-content">
            <strong>{{ capabilityTitle(notification.capability, t, te) }}</strong>
            <span>{{ capabilityDescription(notification.capability, t, te) }}</span>
          </div>
          <Button
            icon="pi pi-times"
            text
            rounded
            :aria-label="t('notifications.dismiss', { title: capabilityTitle(notification.capability, t, te) })"
            @click="dismiss(notification.capability.id)"
          />
        </li>
      </ul>
    </section>
  </div>
</template>
