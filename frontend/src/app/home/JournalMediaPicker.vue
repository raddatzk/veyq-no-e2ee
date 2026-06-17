<script setup lang="ts">
// Picker for a ::media embed: multi-select from this memory's media (thumbnails) + an optional caption. In edit
// mode it seeds from the existing embed and offers Delete (remove the whole token). Emits the chosen ids + caption.
import { ref } from 'vue'
import { t } from '../../i18n/i18n'
import type { GalleryTile } from '../../features/gallery/gallery'

const props = defineProps<{ tiles: GalleryTile[]; selectedIds: string[]; caption: string; editing: boolean }>()
const emit = defineEmits<{ confirm: [ids: string[], caption: string]; cancel: []; remove: [] }>()

const selected = ref<string[]>([...props.selectedIds])
const caption = ref(props.caption)

function toggle(id: string) {
  selected.value = selected.value.includes(id) ? selected.value.filter((x) => x !== id) : [...selected.value, id]
}
</script>

<template>
  <div class="settings-backdrop" @click.self="emit('cancel')">
    <div class="settings-panel journal-picker">
      <div class="settings-head">
        <h2 class="settings-title">{{ t('journal.mediaPickerTitle') }}</h2>
        <button class="btn btn-ghost" type="button" @click="emit('cancel')">{{ t('common.close') }}</button>
      </div>
      <label class="journal-picker-field">
        <span>{{ t('journal.caption') }}</span>
        <input v-model="caption" type="text" :placeholder="t('journal.captionPlaceholder')" />
      </label>
      <div v-if="tiles.length" class="journal-picker-grid">
        <button
          v-for="tile in tiles"
          :key="tile.id"
          type="button"
          class="journal-picker-thumb"
          :class="{ selected: selected.includes(tile.id) }"
          @click="toggle(tile.id)"
        >
          <img :src="tile.url" alt="" />
          <span v-if="selected.includes(tile.id)" class="journal-picker-check">✓</span>
        </button>
      </div>
      <p v-else class="home-empty">{{ t('journal.mediaPickerEmpty') }}</p>
      <div class="journal-picker-actions">
        <button v-if="editing" class="btn btn-ghost" type="button" style="color: var(--app-danger)" @click="emit('remove')">{{ t('common.delete') }}</button>
        <span class="live-spacer" />
        <button class="btn btn-ghost" type="button" @click="emit('cancel')">{{ t('common.cancel') }}</button>
        <button class="btn btn-primary" type="button" :disabled="!selected.length" @click="emit('confirm', selected, caption)">{{ t('journal.insert') }}</button>
      </div>
    </div>
  </div>
</template>
