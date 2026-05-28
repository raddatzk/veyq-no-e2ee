<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Message from 'primevue/message'
import { capabilityDescription, capabilityTitle } from '../../features/settings/capabilities'
import { dismissCapabilityNotification, getCapabilityNotifications } from '../../features/settings/api'
import type { CapabilityNotification } from '../../features/settings/api'

const notifications = ref<CapabilityNotification[]>([])
const { t, te } = useI18n()

onMounted(loadNotifications)

async function loadNotifications() {
  notifications.value = await getCapabilityNotifications().catch(() => [])
}

async function dismiss(capabilityId: string) {
  await dismissCapabilityNotification(capabilityId)
  notifications.value = notifications.value.filter((item) => item.capability.id !== capabilityId)
}
</script>

<template>
  <section v-if="notifications.length" class="capability-notices" aria-live="polite">
    <Message
      v-for="notification in notifications"
      :key="notification.capability.id"
      severity="info"
      :closable="false"
      class="capability-notice"
    >
      <div class="capability-notice-content">
        <div>
          <strong>{{ capabilityTitle(notification.capability, t, te) }}</strong>
          <span>{{ capabilityDescription(notification.capability, t, te) }}</span>
        </div>
        <Button
          icon="pi pi-times"
          text
          rounded
          :aria-label="capabilityTitle(notification.capability, t, te)"
          @click="dismiss(notification.capability.id)"
        />
      </div>
    </Message>
  </section>
</template>
