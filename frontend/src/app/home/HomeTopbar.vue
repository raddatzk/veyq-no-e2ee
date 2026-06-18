<script setup lang="ts">
import { computed } from 'vue'
import { t } from '../../i18n/i18n'

defineProps<{
  crumbs: { id?: string; name: string; slug?: string }[]
  searchEnabled: boolean
}>()

const emit = defineEmits<{
  gotoCrumb: [index: number]
  openSearch: []
}>()

const shortcutLabel = computed(() => (isMacPlatform() ? 'Cmd K' : 'Ctrl K'))

function isMacPlatform(): boolean {
  return typeof navigator !== 'undefined' && /Mac|iPhone|iPad/.test(navigator.platform)
}
</script>

<template>
  <header class="home-topbar">
    <nav class="crumbs">
      <template v-for="(c, i) in crumbs" :key="i">
        <span v-if="i > 0" class="crumb-sep">/</span>
        <button class="crumb" :class="{ current: i === crumbs.length - 1 }" @click="emit('gotoCrumb', i)">{{ c.name }}</button>
      </template>
    </nav>
    <button
      class="home-search"
      type="button"
      :disabled="!searchEnabled"
      @click="searchEnabled && emit('openSearch')"
    >
      <span class="home-search-icon" aria-hidden="true" />
      <span class="home-search-placeholder">{{ t('home.search') }}</span>
      <kbd>{{ shortcutLabel }}</kbd>
    </button>
  </header>
</template>
