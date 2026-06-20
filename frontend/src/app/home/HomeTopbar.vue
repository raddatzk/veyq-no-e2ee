<script setup lang="ts">
import { computed } from 'vue'
import { t } from '../../i18n/i18n'

defineProps<{
  crumbs: { id?: string; name: string; slug?: string }[]
  searchEnabled: boolean
  compactView: boolean
}>()

const emit = defineEmits<{
  gotoCrumb: [index: number]
  openSearch: []
  toggleCompactView: []
  openSettings: []
}>()

const shortcutLabel = computed(() => (isMacPlatform() ? '⌘K' : 'Ctrl K'))

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
    <div class="home-topbar-actions">
      <button
        class="home-topbar-icon"
        type="button"
        :class="{ active: compactView }"
        :title="t('home.viewOptions')"
        :aria-label="t('home.viewOptions')"
        :aria-pressed="compactView"
        @click="emit('toggleCompactView')"
      >
        <span class="home-topbar-list-icon" aria-hidden="true"></span>
      </button>
      <button class="home-topbar-icon" type="button" :title="t('home.settings')" :aria-label="t('home.settings')" @click="emit('openSettings')">
        <span class="home-topbar-gear-icon" aria-hidden="true"></span>
      </button>
    </div>
  </header>
</template>
