<script setup lang="ts">
// Per-device map-tile consent (operator-blind, localStorage only). Loading map tiles reveals coarse location
// to a third party, so it is gated behind this opt-in; the choice never reaches the server.
import { useI18n } from 'vue-i18n'
import { mapTilesConsent } from '../../../features/account/privacy/mapConsent'

const { t } = useI18n()
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
        <input v-model="mapTilesConsent" type="checkbox" />
        <span>
          <strong>{{ t('privacy.osmProviderTitle') }}</strong>
          <small>{{ t('privacy.osmDescription') }}</small>
        </span>
      </label>
    </section>

  </div>
</template>
