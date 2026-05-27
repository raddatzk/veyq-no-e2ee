<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import {
  disableOsmTiles,
  enableOsmTiles,
  hasOsmTileConsent
} from '../../shared/privacy/osmTiles'
import { useAuthState } from '../../shared/state/useAuthState'

const { t } = useI18n()
const { authState } = useAuthState()
const emit = defineEmits<{
  complete: []
}>()
const userId = authState.user?.userId ?? null
const osmTilesEnabled = ref(hasOsmTileConsent(userId))

function saveSelection() {
  applyConsent()
  emit('complete')
}

function acceptAll() {
  osmTilesEnabled.value = true
  saveSelection()
}

function acceptNone() {
  osmTilesEnabled.value = false
  saveSelection()
}

function applyConsent() {
  if (osmTilesEnabled.value) {
    enableOsmTiles(userId)
  } else {
    disableOsmTiles(userId)
  }
}
</script>

<template>
  <fieldset class="registration-privacy-optins">
    <legend>{{ t('privacy.registrationTitle') }}</legend>
    <p>{{ t('privacy.registrationCopy') }}</p>

    <label class="checkbox-row registration-privacy-option">
      <input v-model="osmTilesEnabled" type="checkbox" />
      <span>{{ t('privacy.osmTitle') }}</span>
    </label>

    <div class="button-row">
      <Button
        type="button"
        :label="t('privacy.acceptAll')"
        icon="pi pi-check"
        @click="acceptAll"
      />
      <Button
        type="button"
        :label="t('privacy.acceptNone')"
        severity="secondary"
        outlined
        @click="acceptNone"
      />
      <Button
        type="button"
        :label="t('common.save')"
        severity="secondary"
        @click="saveSelection"
      />
    </div>
  </fieldset>
</template>
