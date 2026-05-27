<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import Message from 'primevue/message'
import ProgressSpinner from 'primevue/progressspinner'
import {
  addMemoryParticipant,
  listMemoryParticipantCandidates,
  removeMemoryParticipant,
  type MemoryParticipantSummary
} from '../api'

const visible = defineModel<boolean>('visible', { default: false })
const props = defineProps<{
  tenantName: string
  memoryId: string
  participants: MemoryParticipantSummary[]
}>()
const emit = defineEmits<{
  saved: []
}>()

const { t } = useI18n()
const candidates = ref<MemoryParticipantSummary[]>([])
const selectedIds = ref<Set<number>>(new Set())
const loading = ref(false)
const saving = ref(false)
const error = ref('')

const currentIds = computed(() => new Set(props.participants.map((participant) => participant.userId)))
const orderedCandidates = computed(() =>
  [...candidates.value].sort((left, right) =>
    left.displayName.localeCompare(right.displayName, undefined, { sensitivity: 'base' }) ||
    left.permalink.localeCompare(right.permalink)
  )
)

watch(visible, (isVisible) => {
  if (isVisible) void loadCandidates()
}, { immediate: true })

async function loadCandidates() {
  loading.value = true
  error.value = ''
  selectedIds.value = new Set(currentIds.value)
  try {
    candidates.value = await listMemoryParticipantCandidates(props.tenantName, props.memoryId)
  } catch {
    error.value = t('memory.participantsLoadFailed')
  } finally {
    loading.value = false
  }
}

function toggleCandidate(userId: number) {
  const next = new Set(selectedIds.value)
  if (next.has(userId)) {
    next.delete(userId)
  } else {
    next.add(userId)
  }
  selectedIds.value = next
}

async function saveParticipants() {
  saving.value = true
  error.value = ''
  try {
    const nextIds = selectedIds.value
    const toAdd = [...nextIds].filter((userId) => !currentIds.value.has(userId))
    const toRemove = [...currentIds.value].filter((userId) => !nextIds.has(userId))
    for (const userId of toAdd) {
      await addMemoryParticipant(props.tenantName, props.memoryId, userId)
    }
    for (const userId of toRemove) {
      await removeMemoryParticipant(props.tenantName, props.memoryId, userId)
    }
    visible.value = false
    emit('saved')
  } catch {
    error.value = t('memory.participantsSaveFailed')
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <Dialog v-model:visible="visible" modal :header="t('memory.participantsManage')" class="tenant-dialog participants-dialog">
    <div class="participants-dialog-body">
      <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>
      <div v-if="loading" class="memory-loading">
        <ProgressSpinner class="small-spinner" />
        <span>{{ t('memory.participantsLoading') }}</span>
      </div>
      <div v-else-if="orderedCandidates.length" class="participants-candidate-list">
        <label
          v-for="candidate in orderedCandidates"
          :key="candidate.userId"
          class="participants-candidate"
        >
          <input
            type="checkbox"
            :checked="selectedIds.has(candidate.userId)"
            @change="toggleCandidate(candidate.userId)"
          />
          <span class="participants-candidate-avatar">
            <img v-if="candidate.avatarUrl" :src="candidate.avatarUrl" alt="" />
            <span v-else>{{ candidate.initials }}</span>
          </span>
          <span>
            <strong>{{ candidate.displayName }}</strong>
            <small>~{{ candidate.permalink }}</small>
          </span>
        </label>
      </div>
      <p v-else class="memory-empty">{{ t('memory.participantsNoCandidates') }}</p>
    </div>
    <template #footer>
      <Button
        icon="pi pi-check"
        :label="t('common.save')"
        :loading="saving"
        :disabled="loading"
        @click="saveParticipants"
      />
      <Button
        icon="pi pi-times"
        :label="t('common.cancel')"
        severity="secondary"
        outlined
        :disabled="saving"
        @click="visible = false"
      />
    </template>
  </Dialog>
</template>

<style scoped>
.participants-dialog-body {
  display: grid;
  gap: 1rem;
}

.participants-candidate-list {
  display: grid;
  gap: 0.55rem;
}

.participants-candidate {
  display: grid;
  grid-template-columns: auto auto 1fr;
  align-items: center;
  gap: 0.65rem;
  padding: 0.55rem;
  border: 1px solid var(--app-border);
  border-radius: 8px;
}

.participants-candidate-avatar {
  display: grid;
  place-items: center;
  width: 2rem;
  height: 2rem;
  border-radius: 50%;
  background: var(--app-accent-soft);
  color: var(--app-accent-strong);
  font-size: 0.78rem;
  font-weight: 800;
  overflow: hidden;
}

.participants-candidate-avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.participants-candidate strong,
.participants-candidate small {
  display: block;
}

.participants-candidate small {
  color: var(--app-muted);
}
</style>
