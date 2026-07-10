<script setup lang="ts">
// A compact per-locale markdown editor (cr-0116) — `v-model` a `{ locale: markdown }` map. Ported (simplified)
// from the legacy `LocaleMarkdownEditor`: add a language, edit each locale in a textarea, remove a locale. veyq
// ships de/en, so the addable set is just those not yet present.
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import type { LocaleMap } from '../share'

const props = defineProps<{ modelValue: LocaleMap }>()
const emit = defineEmits<{ 'update:modelValue': [LocaleMap] }>()
const { t } = useI18n()

const ALL_LOCALES = ['de', 'en'] as const
const present = computed(() => Object.keys(props.modelValue))
const addable = computed(() => ALL_LOCALES.filter((l) => !present.value.includes(l)))
const localeLabel = (l: string) => (l === 'de' ? t('terms.localeDe') : t('terms.localeEn'))

function setText(locale: string, text: string) {
  emit('update:modelValue', { ...props.modelValue, [locale]: text })
}
function addLocale(locale: string) {
  if (props.modelValue[locale] !== undefined) return
  emit('update:modelValue', { ...props.modelValue, [locale]: '' })
}
function removeLocale(locale: string) {
  const next = { ...props.modelValue }
  delete next[locale]
  emit('update:modelValue', next)
}
</script>

<template>
  <div class="locale-md-editor">
    <div v-for="loc in present" :key="loc" class="locale-md-row">
      <div class="locale-md-row-head">
        <span class="locale-md-lang">{{ localeLabel(loc) }}</span>
        <Button
          type="button"
          icon="pi pi-times"
          text
          severity="secondary"
          size="small"
          :aria-label="t('common.cancel')"
          @click="removeLocale(loc)"
        />
      </div>
      <textarea
        :value="modelValue[loc]"
        class="locale-md-input"
        rows="3"
        :placeholder="t('share.notePlaceholder')"
        @input="setText(loc, ($event.target as HTMLTextAreaElement).value)"
      ></textarea>
    </div>
    <p v-if="!present.length" class="locale-md-empty">{{ t('share.noteEmpty') }}</p>
    <div v-if="addable.length" class="locale-md-add">
      <Button
        v-for="loc in addable"
        :key="loc"
        type="button"
        :label="t('share.noteAddLang', { lang: localeLabel(loc) })"
        icon="pi pi-plus"
        text
        size="small"
        @click="addLocale(loc)"
      />
    </div>
  </div>
</template>

<style scoped>
.locale-md-editor {
  display: grid;
  gap: 0.6rem;
}
.locale-md-row {
  display: grid;
  gap: 0.3rem;
}
.locale-md-row-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.locale-md-lang {
  font-size: 0.82rem;
  font-weight: 600;
  color: var(--app-muted);
}
.locale-md-input {
  width: 100%;
  box-sizing: border-box;
  padding: 0.45rem 0.6rem;
  border: 1px solid var(--app-line);
  border-radius: 8px;
  background: var(--app-surface);
  color: var(--app-text);
  font: inherit;
  font-size: 0.88rem;
  resize: vertical;
}
.locale-md-empty {
  margin: 0;
  color: var(--app-muted);
  font-size: 0.85rem;
}
.locale-md-add {
  display: flex;
  gap: 0.5rem;
  flex-wrap: wrap;
}
</style>
