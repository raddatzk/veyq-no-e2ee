<script setup lang="ts">
import { ref, watch } from 'vue'
import { session } from '../session'
import { t } from '../../i18n/i18n'
import { searchNodes, type SearchHit } from '../../features/search/search'
import type { ActiveSpace } from '../../features/home/home'

const props = defineProps<{ active: ActiveSpace }>()
const emit = defineEmits<{ close: []; select: [hit: SearchHit] }>()

const query = ref('')
const hits = ref<SearchHit[]>([])
let seq = 0

watch(query, async (q) => {
  const mine = ++seq
  const result = await searchNodes(session.apiBase, session.unlocked!, props.active, q)
  if (mine === seq) hits.value = result
})
</script>

<template>
  <div class="search-backdrop" @click.self="emit('close')">
    <div class="search-panel">
      <input v-model="query" class="search-input" type="search" :placeholder="t('search.placeholder')" autofocus @keydown.esc="emit('close')" />
      <ul v-if="hits.length" class="search-results">
        <li v-for="hit in hits" :key="hit.id">
          <button class="search-hit" @click="emit('select', hit)">
            <span class="search-hit-icon">{{ hit.type === 'FOLDER' ? '📁' : '🖼️' }}</span>
            <span class="search-hit-name">{{ hit.name }}</span>
            <span class="search-hit-type">{{ hit.type === 'FOLDER' ? t('search.folder') : t('search.memory') }}</span>
          </button>
        </li>
      </ul>
      <div v-else-if="query" class="search-empty">{{ t('search.nothing') }}</div>
    </div>
  </div>
</template>
