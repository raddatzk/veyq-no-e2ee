<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { session } from '../session'
import { t } from '../../i18n/i18n'
import { countMedia, downloadMemoryZip } from '../../features/download/download'
import type { ActiveSpace, MemoryNode } from '../../features/home/home'

const props = defineProps<{ active: ActiveSpace; memory: MemoryNode; name: string }>()
const emit = defineEmits<{ close: [] }>()

const countOwn = ref<number | null>(null)
const countAll = ref<number | null>(null)
const busy = ref(false)

onMounted(async () => {
  countOwn.value = await countMedia(session.apiBase, session.unlocked!, props.active, props.memory, false)
  countAll.value = await countMedia(session.apiBase, session.unlocked!, props.active, props.memory, true)
})

async function download(withChapters: boolean) {
  busy.value = true
  try {
    await downloadMemoryZip(session.apiBase, session.unlocked!, props.active, props.memory, props.name, withChapters)
    emit('close')
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="settings-backdrop" @click.self="emit('close')">
    <div class="settings-panel download-panel">
      <div class="settings-head">
        <h2 class="settings-title">{{ t('download.title') }}</h2>
        <button class="btn btn-ghost" type="button" @click="emit('close')">{{ t('common.close') }}</button>
      </div>
      <button class="dl-option" type="button" :disabled="busy" @click="download(false)">
        <span class="dl-icon dl-icon-download" aria-hidden="true"></span>
        <span class="dl-text"><strong>{{ t('download.onlyThis') }}</strong><small>{{ t('download.onlyThisSub', { n: countOwn ?? '…' }) }}</small></span>
      </button>
      <button class="dl-option" type="button" :disabled="busy" @click="download(true)">
        <span class="dl-icon dl-icon-folder" aria-hidden="true"></span>
        <span class="dl-text"><strong>{{ t('download.withChapters') }}</strong><small>{{ t('download.withChaptersSub', { n: countAll ?? '…' }) }}</small></span>
      </button>
      <div v-if="busy" class="home-empty">{{ t('download.building') }}</div>
    </div>
  </div>
</template>
