<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import InputText from 'primevue/inputtext'
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
const searchQuery = ref('')
const selectedIds = ref<Set<number>>(new Set())
const loading = ref(false)
const saving = ref(false)
const error = ref('')

const currentIds = computed(() => new Set(props.participants.map((participant) => participant.userId)))
const selectedCount = computed(() => selectedIds.value.size)
const orderedCandidates = computed(() =>
  [...candidates.value].sort((left, right) =>
    left.displayName.localeCompare(right.displayName, undefined, { sensitivity: 'base' }) ||
    left.permalink.localeCompare(right.permalink)
  )
)
const filteredCandidates = computed(() => {
  const query = searchQuery.value.trim().toLocaleLowerCase()
  if (!query) return orderedCandidates.value
  return orderedCandidates.value.filter((candidate) =>
    candidate.displayName.toLocaleLowerCase().includes(query) ||
    candidate.permalink.toLocaleLowerCase().includes(query)
  )
})

watch(visible, (isVisible) => {
  if (isVisible) void loadCandidates()
}, { immediate: true })

async function loadCandidates() {
  loading.value = true
  error.value = ''
  searchQuery.value = ''
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
      <div class="participants-dialog-intro">
        <p>{{ t('memory.participantsDialogCopy') }}</p>
        <span v-if="!loading" class="veyq-badge muted">
          {{ t('memory.participantsSelectedCount', { count: selectedCount }) }}
        </span>
      </div>
      <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>
      <div v-if="loading" class="veyq-loading">
        <ProgressSpinner class="small-spinner" />
        <span>{{ t('memory.participantsLoading') }}</span>
      </div>
      <template v-else-if="orderedCandidates.length">
        <label class="participants-search">
          <span class="participants-search-icon pi pi-search" aria-hidden="true" />
          <InputText
            v-model="searchQuery"
            type="search"
            autocomplete="off"
            :placeholder="t('memory.participantsSearchPlaceholder')"
            :aria-label="t('memory.participantsSearchPlaceholder')"
          />
        </label>
        <div v-if="filteredCandidates.length" class="participants-candidate-list">
          <label
            v-for="candidate in filteredCandidates"
            :key="candidate.userId"
            class="participants-candidate"
            :class="{ 'participants-candidate-selected': selectedIds.has(candidate.userId) }"
          >
            <input
              type="checkbox"
              class="participants-candidate-input"
              :checked="selectedIds.has(candidate.userId)"
              @change="toggleCandidate(candidate.userId)"
            />
            <span class="participants-candidate-check" aria-hidden="true">
              <i class="pi pi-check" />
            </span>
            <span class="participants-candidate-avatar">
              <img v-if="candidate.avatarUrl" :src="candidate.avatarUrl" alt="" />
              <span v-else>{{ candidate.initials }}</span>
            </span>
            <span class="participants-candidate-text">
              <strong>{{ candidate.displayName }}</strong>
              <small>~{{ candidate.permalink }}</small>
            </span>
          </label>
        </div>
        <p v-else class="veyq-empty participants-search-empty">
          <span class="pi pi-search" aria-hidden="true" />
          <span>{{ t('memory.participantsSearchEmpty') }}</span>
        </p>
      </template>
      <p v-else class="veyq-empty">{{ t('memory.participantsNoCandidates') }}</p>
    </div>
    <template #footer>
      <div class="veyq-dialog-action-row">
        <Button
          icon="pi pi-times"
          :label="t('common.cancel')"
          severity="secondary"
          outlined
          :disabled="saving"
          @click="visible = false"
        />
        <Button
          icon="pi pi-check"
          :label="t('common.save')"
          :loading="saving"
          :disabled="loading"
          @click="saveParticipants"
        />
      </div>
    </template>
  </Dialog>
</template>

<style scoped>
:global(.participants-dialog.p-dialog) {
  width: min(520px, calc(100vw - 2rem));
}

.participants-dialog-body {
  display: grid;
  gap: 1.1rem;
  padding-top: 0.15rem;
}

.participants-dialog-intro {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.85rem;
}

.participants-dialog-intro p {
  margin: 0;
  color: var(--app-muted);
  font-size: 0.92rem;
  line-height: 1.45;
}

.participants-search {
  position: relative;
  display: block;
}

.participants-search :deep(.p-inputtext) {
  width: 100%;
  padding-left: 2.35rem;
}

.participants-search-icon {
  position: absolute;
  top: 50%;
  left: 0.82rem;
  z-index: 1;
  color: var(--app-muted);
  font-size: 0.9rem;
  transform: translateY(-50%);
  pointer-events: none;
}

.participants-search-empty {
  min-height: 72px;
}

.participants-candidate-list {
  display: grid;
  gap: 0.65rem;
}

.participants-candidate {
  position: relative;
  display: grid;
  grid-template-columns: auto auto 1fr;
  align-items: center;
  gap: 0.75rem;
  min-width: 0;
  padding: 0.7rem 0.75rem;
  border: 1px solid var(--app-line);
  border-radius: var(--app-radius-sm);
  background: var(--app-surface);
  cursor: pointer;
  transition:
    border-color 140ms ease,
    background 140ms ease,
    box-shadow 140ms ease;
}

.participants-candidate:hover {
  background: var(--app-hover);
}

.participants-candidate:has(.participants-candidate-input:focus-visible) {
  outline: 2px solid var(--app-focus);
  outline-offset: 2px;
}

.participants-candidate-selected {
  border-color: color-mix(in srgb, var(--app-accent) 72%, var(--app-line));
  background: color-mix(in srgb, var(--app-accent-soft) 72%, var(--app-surface));
  box-shadow: inset 0 0 0 1px color-mix(in srgb, var(--app-accent) 32%, transparent);
}

.participants-candidate-input {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0 0 0 0);
  clip-path: inset(50%);
  white-space: nowrap;
}

.participants-candidate-check {
  display: grid;
  place-items: center;
  width: 1.55rem;
  height: 1.55rem;
  border: 1px solid var(--app-line);
  border-radius: 999px;
  background: var(--app-surface);
  color: transparent;
  font-size: 0.72rem;
  transition:
    border-color 140ms ease,
    background 140ms ease,
    color 140ms ease;
}

.participants-candidate-selected .participants-candidate-check {
  border-color: var(--app-accent);
  background: var(--app-accent);
  color: var(--app-on-strong);
}

.participants-candidate-avatar {
  display: grid;
  place-items: center;
  width: 2.65rem;
  height: 2.65rem;
  border-radius: 50%;
  background: var(--app-accent-soft);
  color: var(--app-accent);
  font-size: 0.9rem;
  font-weight: 800;
  overflow: hidden;
}

.participants-candidate-avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.participants-candidate-text {
  display: grid;
  min-width: 0;
  gap: 0.12rem;
}

.participants-candidate-text strong,
.participants-candidate-text small {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.participants-candidate-text strong {
  color: var(--app-text);
  font-size: 0.98rem;
}

.participants-candidate-text small {
  color: var(--app-muted);
  font-size: 0.84rem;
}

@media (max-width: 420px) {
  :global(.participants-dialog.p-dialog) {
    width: min(100vw - 1rem, 420px);
  }

  .participants-dialog-intro {
    align-items: stretch;
    flex-direction: column;
  }
}
</style>
