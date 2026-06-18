<script setup lang="ts">
// Picker for a ::track (multi-select), ::chapter (single-select), or ::playlist (single-select) embed: pick from
// this memory's tracks, child chapters, or playlists + an optional label. In edit mode it seeds from the
// existing embed and offers Delete.
import { computed, ref } from 'vue'
import { t } from '../../i18n/i18n'

const props = defineProps<{ kind: 'track' | 'chapter' | 'playlist'; items: { id: string; name: string }[]; selectedIds: string[]; label: string; editing: boolean }>()
const emit = defineEmits<{ confirm: [ids: string[], label: string]; cancel: []; remove: [] }>()

const selected = ref<string[]>([...props.selectedIds])
const label = ref(props.label)
const single = computed(() => props.kind === 'chapter' || props.kind === 'playlist')
const icon = computed(() => props.kind === 'track' ? '🛤' : props.kind === 'playlist' ? '🎵' : '🗂')
const title = computed(() => props.kind === 'track' ? t('journal.trackPickerTitle') : props.kind === 'playlist' ? t('journal.playlistPickerTitle') : t('journal.chapterPickerTitle'))

function toggle(id: string) {
  if (single.value) { selected.value = [id]; return }
  selected.value = selected.value.includes(id) ? selected.value.filter((x) => x !== id) : [...selected.value, id]
}
</script>

<template>
  <div class="settings-backdrop" @click.self="emit('cancel')">
    <div class="settings-panel journal-picker">
      <div class="settings-head">
        <h2 class="settings-title">{{ title }}</h2>
        <button class="btn btn-ghost" type="button" @click="emit('cancel')">{{ t('common.close') }}</button>
      </div>
      <label class="journal-picker-field">
        <span>{{ t('journal.label') }}</span>
        <input v-model="label" type="text" :placeholder="t('journal.labelPlaceholder')" />
      </label>
      <ul v-if="items.length" class="search-results">
        <li v-for="it in items" :key="it.id">
          <button class="search-hit" :class="{ selected: selected.includes(it.id) }" @click="toggle(it.id)">
            <span class="search-hit-icon">{{ icon }}</span>
            <span class="search-hit-name">{{ it.name }}</span>
            <span v-if="selected.includes(it.id)" class="journal-picker-check">✓</span>
          </button>
        </li>
      </ul>
      <p v-else class="home-empty">{{ t('journal.pickerEmpty') }}</p>
      <div class="journal-picker-actions">
        <button v-if="editing" class="btn btn-ghost" type="button" style="color: var(--app-danger)" @click="emit('remove')">{{ t('common.delete') }}</button>
        <span class="live-spacer" />
        <button class="btn btn-ghost" type="button" @click="emit('cancel')">{{ t('common.cancel') }}</button>
        <button class="btn btn-primary" type="button" :disabled="!selected.length" @click="emit('confirm', selected, label)">{{ t('journal.insert') }}</button>
      </div>
    </div>
  </div>
</template>
