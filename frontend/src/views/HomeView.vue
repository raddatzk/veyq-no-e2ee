<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import Card from 'primevue/card'

const { t } = useI18n()
</script>

<template>
  <Card>
    <template #content>
      {{ t('tenant.empty') }}
    </template>
  </Card>
</template>
