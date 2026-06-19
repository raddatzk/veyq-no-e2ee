<script setup lang="ts">
import { computed, ref } from 'vue'
import { t } from '../../i18n/i18n'
import type { Participant } from '../../features/people/participants/participants'

type ParticipantCandidate = { userId: string; displayName: string }

const props = defineProps<{
  heroUrl: string | null
  showBack?: boolean
  crumbs: { id: string; name: string }[]
  currentName: string
  mediaCount: number
  participants?: Participant[]
  participantCandidates?: ParticipantCandidate[]
  canManageParticipants?: boolean
  canOpenLinkedParticipants?: boolean
}>()

const emit = defineEmits<{
  back: []
  navigate: [nodeId: string]
  addAnonymousParticipant: []
  addUserParticipant: [userId: string]
  removeParticipant: [participantId: string]
  openParticipant: [participant: Participant]
}>()

const selectedUserId = ref('')
const participantList = computed(() => props.participants ?? [])
const linkedUserIds = computed(() => new Set(participantList.value.map((p) => p.linkedUserId).filter(Boolean) as string[]))
const availableCandidates = computed(() => (props.participantCandidates ?? []).filter((c) => !linkedUserIds.value.has(c.userId)))
function addSelectedUser() {
  const id = selectedUserId.value
  if (!id) return
  emit('addUserParticipant', id)
  selectedUserId.value = ''
}
function openParticipant(participant: Participant) {
  if (!props.canOpenLinkedParticipants || !participant.linkedUserId) return
  emit('openParticipant', participant)
}
</script>

<template>
  <header class="memory-detail-header">
    <div class="memory-detail-cover" :class="{ 'memory-detail-cover-has-image': Boolean(heroUrl) }">
      <img v-if="heroUrl" :src="heroUrl" alt="" />
      <span v-else class="memory-detail-cover-placeholder">...</span>
    </div>

    <div class="memory-detail-heading">
      <div class="detail-nav-row">
        <button v-if="showBack" class="crumb detail-back" @click="emit('back')">{{ t('common.back') }}</button>
        <nav v-if="crumbs.length > 1" class="crumbs detail-crumbs">
          <template v-for="(c, i) in crumbs" :key="c.id">
            <span v-if="i > 0" class="crumb-sep">/</span>
            <button class="crumb" :class="{ current: i === crumbs.length - 1 }" @click="emit('navigate', c.id)">{{ c.name }}</button>
          </template>
        </nav>
      </div>

      <div class="memory-detail-title-row">
        <div class="memory-detail-title-copy">
          <h1 class="detail-title">{{ currentName }}</h1>
        </div>
        <div class="memory-detail-actions">
          <slot />
        </div>
      </div>

      <div class="memory-detail-meta">
        <span>{{ t('detail.mediaCount', { n: mediaCount }) }}</span>
      </div>

      <div v-if="participantList.length || canManageParticipants" class="memory-participants">
        <span class="memory-participants-label">{{ t('people.participants') }}</span>
        <span v-for="p in participantList" :key="p.id" class="participant-chip" :class="{ linked: !!p.linkedUserId }">
          <button
            v-if="p.linkedUserId && canOpenLinkedParticipants"
            class="participant-link"
            type="button"
            @click="openParticipant(p)"
          >
            <span class="participant-name">{{ p.displayName }}</span>
            <span class="participant-kind">{{ t('people.linked') }}</span>
          </button>
          <template v-else>
            <span class="participant-name">{{ p.displayName }}</span>
            <span v-if="p.linkedUserId" class="participant-kind">{{ t('people.linked') }}</span>
          </template>
          <button
            v-if="canManageParticipants"
            class="participant-remove"
            type="button"
            :aria-label="t('people.removeParticipant', { name: p.displayName })"
            @click="emit('removeParticipant', p.id)"
          >×</button>
        </span>
        <select
          v-if="canManageParticipants && availableCandidates.length"
          v-model="selectedUserId"
          class="participant-select"
          :aria-label="t('people.addUser')"
          @change="addSelectedUser"
        >
          <option value="">{{ t('people.addUser') }}</option>
          <option v-for="c in availableCandidates" :key="c.userId" :value="c.userId">{{ c.displayName }}</option>
        </select>
        <button v-if="canManageParticipants" class="btn btn-ghost participant-add" type="button" @click="emit('addAnonymousParticipant')">
          {{ t('people.addAnonymous') }}
        </button>
      </div>
    </div>
  </header>
</template>
