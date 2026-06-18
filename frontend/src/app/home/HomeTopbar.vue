<script setup lang="ts">
import { t } from '../../i18n/i18n'

defineProps<{
  crumbs: { id?: string; name: string; slug?: string }[]
  searchEnabled: boolean
}>()

const emit = defineEmits<{
  gotoCrumb: [index: number]
  openSearch: []
}>()
</script>

<template>
  <header class="home-topbar">
    <nav class="crumbs">
      <template v-for="(c, i) in crumbs" :key="i">
        <span v-if="i > 0" class="crumb-sep">/</span>
        <button class="crumb" :class="{ current: i === crumbs.length - 1 }" @click="emit('gotoCrumb', i)">{{ c.name }}</button>
      </template>
    </nav>
    <input
      class="home-search"
      type="search"
      :placeholder="t('home.search')"
      :disabled="!searchEnabled"
      readonly
      @focus="searchEnabled && emit('openSearch')"
      @click="searchEnabled && emit('openSearch')"
    />
  </header>
</template>
