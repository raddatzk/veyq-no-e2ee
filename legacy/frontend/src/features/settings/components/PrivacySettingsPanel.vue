<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  disableOsmTiles,
  enableOsmTiles,
  hasOsmTileConsent
} from '../../../shared/privacy/osmTiles'
import { useAuthState } from '../../../shared/state/useAuthState'

const { t } = useI18n()
const { authState } = useAuthState()
const userId = authState.user?.userId ?? null
const osmTilesEnabled = ref(hasOsmTileConsent(userId))

function enableTiles() {
  enableOsmTiles(userId)
  osmTilesEnabled.value = true
}

function disableTiles() {
  disableOsmTiles(userId)
  osmTilesEnabled.value = false
}

function updateTiles(event: Event) {
  if ((event.target as HTMLInputElement).checked) {
    enableTiles()
  } else {
    disableTiles()
  }
}

</script>

<template>
  <div class="privacy-settings-dialog-content">
    <p>{{ t('privacy.browserSettings') }}</p>

    <section class="privacy-settings-group">
      <div>
        <h2>{{ t('privacy.mapsGroupTitle') }}</h2>
        <p>{{ t('privacy.mapsGroupDescription') }}</p>
      </div>
      <label class="privacy-provider-checkbox">
        <input type="checkbox" :checked="osmTilesEnabled" @change="updateTiles" />
        <span>
          <strong>{{ t('privacy.osmProviderTitle') }}</strong>
          <small>{{ t('privacy.osmDescription') }}</small>
        </span>
      </label>
    </section>

  </div>
</template>
