<script setup lang="ts">
import { computed, getCurrentInstance, watch } from 'vue'
import DatePicker, { type DatePickerBlurEvent } from 'primevue/datepicker'
import type { PrimeVueConfiguration } from 'primevue/config'
import { useI18n } from 'vue-i18n'
import { applyPrimeVueLocale } from '../../i18n/primevueLocale'
import { isoDateToLocalDate, localDateToIsoDate } from '../utils/isoDate'
import type { SupportedLocale } from '../../i18n'

const model = defineModel<string>({ required: true })
defineProps<{
  inputId?: string
  ariaLabel?: string
  required?: boolean
}>()

const { locale } = useI18n()
const primeVueConfig = resolvePrimeVueConfig()
const dateFormat = computed(() => (locale.value === 'en' ? 'mm/dd/yy' : 'dd.mm.yy'))
const dateValue = computed<Date | null>({
  get: () => isoDateToLocalDate(model.value),
  set: (value) => {
    model.value = localDateToIsoDate(value)
  }
})

watch(
  locale,
  (value) => {
    if (primeVueConfig) applyPrimeVueLocale(primeVueConfig, value as SupportedLocale)
  },
  { immediate: true }
)

function resolvePrimeVueConfig(): PrimeVueConfiguration | null {
  const primeVue = getCurrentInstance()?.appContext.config.globalProperties.$primevue as
    | { config?: PrimeVueConfiguration }
    | undefined
  return primeVue?.config ?? null
}

function updateManualValue(event: DatePickerBlurEvent) {
  const parsed = parseManualDate(event.value, locale.value as SupportedLocale)
  if (parsed) model.value = localDateToIsoDate(parsed)
}

function parseManualDate(value: string, currentLocale: SupportedLocale): Date | null {
  const trimmed = value.trim()
  const isoDate = isoDateToLocalDate(trimmed)
  if (isoDate) return isoDate

  const displayPattern = currentLocale === 'en' ? /^(\d{1,2})\/(\d{1,2})\/(\d{4})$/ : /^(\d{1,2})\.(\d{1,2})\.(\d{4})$/
  const match = displayPattern.exec(trimmed)
  if (!match) return null

  const month = currentLocale === 'en' ? Number(match[1]) : Number(match[2])
  const day = currentLocale === 'en' ? Number(match[2]) : Number(match[1])
  const year = Number(match[3])
  const date = new Date(year, month - 1, day)

  return date.getFullYear() === year && date.getMonth() === month - 1 && date.getDate() === day
    ? date
    : null
}
</script>

<template>
  <DatePicker
    v-model="dateValue"
    :input-id="inputId"
    :aria-label="ariaLabel"
    :date-format="dateFormat"
    :required="required"
    append-to="self"
    class="veyq-date-field"
    fluid
    icon="pi pi-calendar"
    icon-display="input"
    show-icon
    @blur="updateManualValue"
  />
</template>
