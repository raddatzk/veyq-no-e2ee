<script setup lang="ts">
import { computed, watch } from 'vue'
import { useRoute } from 'vue-router'
import { canonicalAppURL, upsertSmartAppBanner } from '../../shared/utils/appLinks'
import { useServerState } from '../../shared/state/useServerState'

const route = useRoute()
const { serverState } = useServerState()

const canonicalURL = computed(() => canonicalAppURL(window.location))
const iosAppStoreId = computed(() => serverState.mobileApps.iosAppStoreId || '')

watch(
  [() => route.fullPath, iosAppStoreId],
  () => {
    upsertSmartAppBanner(iosAppStoreId.value, canonicalURL.value)
  },
  { immediate: true }
)
</script>

<template>
  <span v-if="false" aria-hidden="true"></span>
</template>
