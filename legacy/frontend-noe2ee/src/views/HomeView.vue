<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import Card from 'primevue/card'

const { t } = useI18n()
</script>

<template>
  <Card class="empty-home-card">
    <template #content>
      <div class="empty-state">
        <span class="pi pi-images empty-state-icon" />
        <div>
          <h1>{{ t('tenant.empty') }}</h1>
          <p>{{ t('tenant.emptyAction') }}</p>
        </div>
      </div>
    </template>
  </Card>
</template>
