<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import type { MemoryParticipantSummary } from '../api'
import { memoryPathToTenantRoute } from '../../../shared/utils/paths'

const props = defineProps<{
  tenantName: string
  participants: MemoryParticipantSummary[]
}>()
const { t } = useI18n()

const container = ref<HTMLElement | null>(null)
const overflowOpen = ref(false)
const visibleCount = ref(0)
let resizeObserver: ResizeObserver | null = null

const orderedParticipants = computed(() =>
  [...props.participants].sort((left, right) =>
    left.displayName.localeCompare(right.displayName, undefined, { sensitivity: 'base' }) ||
    left.permalink.localeCompare(right.permalink)
  )
)
const visibleParticipants = computed(() => orderedParticipants.value.slice(0, visibleCount.value))
const hiddenParticipants = computed(() => orderedParticipants.value.slice(visibleCount.value))

onMounted(() => {
  resizeObserver = new ResizeObserver(updateVisibleCount)
  if (container.value) resizeObserver.observe(container.value)
  updateVisibleCount()
})

onBeforeUnmount(() => {
  resizeObserver?.disconnect()
})

watch(() => props.participants.length, updateVisibleCount)

function participantRoute(participant: MemoryParticipantSummary): string {
  return memoryPathToTenantRoute(props.tenantName, `/~${participant.permalink}`)
}

function updateVisibleCount() {
  const width = container.value?.getBoundingClientRect().width || 0
  if (!width) {
    visibleCount.value = Math.min(orderedParticipants.value.length, 3)
    return
  }
  const reservedOverflowWidth = orderedParticipants.value.length > 1 ? 44 : 0
  const available = Math.max(0, width - reservedOverflowWidth)
  const estimatedChipWidth = 34
  visibleCount.value = Math.max(1, Math.min(orderedParticipants.value.length, Math.floor(available / estimatedChipWidth)))
}

function toggleOverflow(event: MouseEvent) {
  event.stopPropagation()
  overflowOpen.value = !overflowOpen.value
}
</script>

<template>
  <nav v-if="orderedParticipants.length" ref="container" class="memory-participants-strip" aria-label="Memory participants">
    <RouterLink
      v-for="participant in visibleParticipants"
      :key="participant.userId"
      class="memory-participant-chip"
      :to="participantRoute(participant)"
      :title="participant.displayName"
      :aria-label="participant.displayName"
    >
      <img v-if="participant.avatarUrl" class="memory-participant-avatar-image" :src="participant.avatarUrl" alt="" />
      <span v-else class="memory-participant-avatar">{{ participant.initials }}</span>
    </RouterLink>
    <div v-if="hiddenParticipants.length" class="memory-participants-overflow-wrap">
      <Button
        class="memory-participants-overflow"
        icon="pi pi-chevron-right"
        severity="secondary"
        text
        rounded
        size="small"
        :aria-label="t('memory.participantsOverflow', { count: hiddenParticipants.length })"
        aria-haspopup="menu"
        :aria-expanded="overflowOpen"
        @click="toggleOverflow"
      />
      <div v-if="overflowOpen" class="memory-participants-popover" role="menu">
        <RouterLink
          v-for="participant in hiddenParticipants"
          :key="participant.userId"
          class="memory-participant-chip"
          :to="participantRoute(participant)"
          :title="participant.displayName"
          :aria-label="participant.displayName"
          role="menuitem"
        >
          <img v-if="participant.avatarUrl" class="memory-participant-avatar-image" :src="participant.avatarUrl" alt="" />
          <span v-else class="memory-participant-avatar">{{ participant.initials }}</span>
        </RouterLink>
      </div>
    </div>
  </nav>
</template>

<style scoped>
.memory-participants-strip {
  display: flex;
  align-items: center;
  gap: 0.4rem;
  min-width: 0;
  max-width: 100%;
}

.memory-participant-chip {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 1.8rem;
  height: 1.8rem;
  padding: 0;
  border: 1px solid var(--app-border);
  border-radius: 999px;
  color: var(--app-text);
  text-decoration: none;
  background: var(--app-surface);
}

.memory-participant-chip:hover,
.memory-participant-chip:focus-visible {
  border-color: var(--app-accent);
}

.memory-participant-avatar {
  display: grid;
  place-items: center;
  width: 100%;
  height: 100%;
  border-radius: 999px;
  background: var(--app-accent-soft);
  color: var(--app-accent-strong);
  font-size: 0.72rem;
  font-weight: 800;
}

.memory-participant-avatar-image {
  width: 100%;
  height: 100%;
  border-radius: 999px;
  object-fit: cover;
}

.memory-participants-overflow-wrap {
  position: relative;
  flex: 0 0 auto;
}

.memory-participants-popover {
  position: absolute;
  top: calc(100% + 0.35rem);
  right: 0;
  z-index: 20;
  display: flex;
  gap: 0.35rem;
  padding: 0.45rem;
  border: 1px solid var(--app-border);
  border-radius: 999px;
  background: var(--app-surface);
  box-shadow: var(--app-shadow);
}
</style>
